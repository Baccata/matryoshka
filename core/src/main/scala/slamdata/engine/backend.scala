/*
 * Copyright 2014 - 2015 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package slamdata.engine

import slamdata.engine.fp._
import slamdata.engine.fs._
import slamdata.engine.Errors._

import scalaz.{Node => _, Tree => _, _}
import Scalaz._
import scalaz.concurrent.{Node => _, _}

import scalaz.stream.{Writer => _, _}

import slamdata.engine.config._


sealed trait PhaseResult {
  def name: String
}
object PhaseResult {
  import argonaut._
  import Argonaut._

  final case class Error(name: String, value: CompilationError) extends PhaseResult {
    override def toString = name + "\n" + value.toString
  }
  final case class Tree(name: String, value: RenderedTree) extends PhaseResult {
    override def toString = name + "\n" + Show[RenderedTree].shows(value)
  }
  final case class Detail(name: String, value: String) extends PhaseResult {
    override def toString = name + "\n" + value
  }

  implicit def PhaseResultEncodeJson: EncodeJson[PhaseResult] = EncodeJson {
    case PhaseResult.Error(name, value) =>
      Json.obj(
        "name"  := name,
        "error" := value.message
      )
    case PhaseResult.Tree(name, value) =>
      Json.obj(
        "name" := name,
        "tree" := value
      )
    case PhaseResult.Detail(name, value) =>
      Json.obj(
        "name"   := name,
        "detail" := value
      )
  }
}

sealed trait ProcessingError {
  def message: String
}
object ProcessingError {
  type ProcessingTask[A] = ETask[ProcessingError, A]
  implicit val ProcessingErrorShow = Show.showFromToString[ProcessingError]

  object Types {
    final case class PEvalError(error: EvaluationError)
        extends ProcessingError {
      def message = error.message
    }
    final case class PResultError(error: slamdata.engine.Backend.ResultError) extends ProcessingError {
      def message = error.message
    }
    final case class PWriteError(error: slamdata.engine.fs.WriteError)
        extends ProcessingError {
      def message = error.message
    }
    final case class PPathError(error: slamdata.engine.fs.PathError)
        extends ProcessingError {
      def message = error.message
    }
  }

  object PEvalError {
    def apply(error: EvaluationError): ProcessingError = Types.PEvalError(error)
    def unapply(obj: ProcessingError): Option[EvaluationError] = obj match {
      case Types.PEvalError(error) => Some(error)
      case _                       => None
    }
  }
  object PResultError {
    def apply(error: slamdata.engine.Backend.ResultError): ProcessingError = Types.PResultError(error)
    def unapply(obj: ProcessingError): Option[slamdata.engine.Backend.ResultError] = obj match {
      case Types.PResultError(error) => Some(error)
      case _                         => None
    }
  }
  object PWriteError {
    def apply(error: slamdata.engine.fs.WriteError): ProcessingError = Types.PWriteError(error)
    def unapply(obj: ProcessingError): Option[slamdata.engine.fs.WriteError] = obj match {
      case Types.PWriteError(error) => Some(error)
      case _                        => None
    }
  }
  object PPathError {
    def apply(error: slamdata.engine.fs.PathError): ProcessingError = Types.PPathError(error)
    def unapply(obj: ProcessingError): Option[slamdata.engine.fs.PathError] = obj match {
      case Types.PPathError(error) => Some(error)
      case _                       => None
    }
  }
}

sealed trait Backend { self =>
  import Backend._
  import PathError._
  import ProcessingError._

  def checkCompatibility: ETask[EnvironmentError, Unit]

  /**
   * Executes a query, producing a compilation log and the path where the result
   * can be found.
   */
  def run(req: QueryRequest):
      (Vector[PhaseResult], ETask[EvaluationError, ResultPath]) = {
    val (phases, pathT) = run0(QueryRequest(
      slamdata.engine.sql.SQLParser.mapPathsM[Id](req.query, _.asRelative),
      req.out.map(_.asRelative),
      req.variables))
    phases -> pathT.map {
      case ResultPath.User(path) => ResultPath.User(path.asAbsolute)
      case ResultPath.Temp(path) => ResultPath.Temp(path.asAbsolute)
    }
  }

  def run0(req: QueryRequest):
      (Vector[PhaseResult], ETask[EvaluationError, ResultPath])

  /**
    * Executes a query, returning both a compilation log and a source of values
    * from the result set. If no location was specified for the results, then
    * the temporary result is deleted after being read.
    */
  // FIXME: remove type annotations
  def eval(req: QueryRequest):
      (Vector[PhaseResult], Process[ProcessingTask, Data]) = {
    val (log, outT) = run(req)
    (log,
      Process.eval[ProcessingTask, ResultPath](outT.leftMap(PEvalError(_))).flatMap[ProcessingTask, Data] { out =>
        val results = scanAll(out.path).translate[ProcessingTask](convertError(PResultError(_)))
        out match {
          case ResultPath.Temp(path) => results.cleanUpWith(delete(path).leftMap(PPathError(_)))
          case _                     => results
        }
      })
  }

  /**
    * Prepares a query for execution, returning only a compilation log.
    */
  def evalLog(req: QueryRequest): Vector[PhaseResult] = eval(req)._1

  /**
    * Executes a query, returning only a source of values from the result set.
    * If no location was specified for the results, then the temporary result
    * is deleted after being read.
    */
  def evalResults(req: QueryRequest): Process[ProcessingTask, Data] =
    eval(req)._2

  // Filesystem stuff

  final def scan(path: Path, offset: Long, limit: Option[Long]):
      Process[ETask[ResultError, ?], Data] =
    (offset, limit) match {
      // NB: skip < 0 is an error in the driver
      case (o, _)       if o < 0 => Process.eval[ETask[ResultError, ?], Data](EitherT.left(Task.now(InvalidOffsetError(o))))
      // NB: limit == 0 means no limit, and limit < 0 means request only a single batch (which we use below)
      case (_, Some(l)) if l < 1 => Process.eval[ETask[ResultError, ?], Data](EitherT.left(Task.now(InvalidLimitError(l))))
      case _                     => scan0(path.asRelative, offset, limit)
    }

  def scan0(path: Path, offset: Long, limit: Option[Long]):
      Process[ETask[ResultError, ?], Data]

  final def scanAll(path: Path) = scan(path, 0, None)

  final def scanTo(path: Path, limit: Long) = scan(path, 0, Some(limit))

  final def scanFrom(path: Path, offset: Long) = scan(path, offset, None)

  def count(path: Path): PathTask[Long] = count0(path.asRelative)

  def count0(path: Path): PathTask[Long]

  /**
    Save a collection of documents at the given path, replacing any previous
    contents, atomically. If any error occurs while consuming input values,
    nothing is written and any previous values are unaffected.
    */
  def save(path: Path, values: Process[Task, Data]): ProcessingTask[Unit] =
    save0(path.asRelative, values)

  def save0(path: Path, values: Process[Task, Data]): ProcessingTask[Unit]

  /**
    Create a new collection of documents at the given path.
    */
  def create(path: Path, values: Process[Task, Data]) =
    // TODO: Fix race condition (#778)
    exists(path).leftMap(PPathError(_)).flatMap(ex =>
      if (ex) EitherT.left[Task, ProcessingError, Unit](Task.now(PPathError(ExistingPathError(path, Some("can’t be created, because it already exists")))))
      else save(path, values))

  /**
    Replaces a collection of documents at the given path. If any error occurs,
    the previous contents should be unaffected.
  */
  def replace(path: Path, values: Process[Task, Data]) =
    // TODO: Fix race condition (#778)
    exists(path).leftMap(PPathError(_)).flatMap(ex =>
      if (ex) save(path, values)
      else EitherT.left[Task, ProcessingError, Unit](Task.now(PPathError(NonexistentPathError(path, Some("can’t be replaced, because it doesn’t exist"))))))

  /**
   Add values to a possibly existing collection. May write some values and not others,
   due to bad input or problems on the backend side. The result stream yields an error
   for each input value that is not written, or no values at all.
   */
  def append(path: Path, values: Process[Task, Data]):
      Process[PathTask, WriteError] =
    append0(path.asRelative, values)

  def append0(path: Path, values: Process[Task, Data]):
      Process[PathTask, WriteError]

  def move(src: Path, dst: Path, semantics: MoveSemantics): PathTask[Unit] =
    move0(src.asRelative, dst.asRelative, semantics)

  def move0(src: Path, dst: Path, semantics: MoveSemantics): PathTask[Unit]

  def delete(path: Path): PathTask[Unit] =
    delete0(path.asRelative)

  def delete0(path: Path): PathTask[Unit]

  def ls(dir: Path): PathTask[Set[FilesystemNode]] =
    dir.file.fold(
      ls0(dir.asRelative))(
      κ(EitherT.left(Task.now(InvalidPathError("Can not ls a file: " + dir)))))

  def ls0(dir: Path): PathTask[Set[FilesystemNode]]

  def ls: PathTask[Set[FilesystemNode]] = ls(Path.Root)

  def lsAll(dir: Path): PathTask[Set[FilesystemNode]] = {
    def descPaths(p: Path): ListT[PathTask, FilesystemNode] =
      ListT[PathTask, FilesystemNode](ls(dir ++ p).map(_.toList)).flatMap { n =>
          val cp = p ++ n.path
          if (cp.pureDir) descPaths(cp) else ListT(liftP(Task.now(List(FilesystemNode(cp, n.typ)))))
      }
    descPaths(Path(".")).run.map(_.toSet)
  }


  def exists(path: Path): PathTask[Boolean] =
    if (path == Path.Root) true.point[PathTask]
    else ls(path.parent).map(_.map(path.parent ++ _.path) contains path)

  def defaultPath: Path
}

/** May be mixed in to implement the query methods of Backend using a Planner and Evaluator. */
trait PlannerBackend[PhysicalPlan] extends Backend {
  import EvaluationError._

  def planner: Planner[PhysicalPlan]
  def evaluator: Evaluator[PhysicalPlan]
  implicit def RP: RenderTree[PhysicalPlan]

  lazy val queryPlanner = planner.queryPlanner(evaluator.compile(_))

  def checkCompatibility = evaluator.checkCompatibility

  def run0(req: QueryRequest): (Vector[PhaseResult], ETask[EvaluationError, ResultPath]) = {
    val (phases, physical) = queryPlanner(req)

    phases ->
      physical.fold[ETask[EvaluationError, ResultPath]](
        κ(EitherT.left(Task.now(CompileFailed()))),
        plan => for {
          rez    <- evaluator.execute(plan)
          renamed <- (rez, req.out) match {
            case (ResultPath.Temp(path), Some(out)) => for {
              _ <- move(path, out, Backend.Overwrite).leftMap(EvalPathError(_))
            } yield ResultPath.User(out)
            case _ => liftE[EvaluationError](Task.now(rez))
          }
        } yield renamed)
  }
}

object Backend {
  import EnvironmentError._

  sealed trait ResultError {
    def message: String
  }
  type ResTask[A] = ETask[ResultError, A]
  final case class ResultPathError(error: slamdata.engine.fs.PathError)
      extends ResultError {
    def message = error.message
  }
  trait ScanError extends ResultError
  final case class InvalidOffsetError(value: Long) extends ScanError {
    def message = "invalid offset: " + value + " (must be >= 0)"
  }
  final case class InvalidLimitError(value: Long) extends ScanError {
    def message = "invalid limit: " + value + " (must be >= 1)"
  }

  type PathTask[X] = ETask[PathError, X]
  val liftP = new (Task ~> PathTask) {
    def apply[T](t: Task[T]): PathTask[T] = EitherT.right(t)
  }

  implicit class PrOpsTask[O](self: Process[PathTask, O])
      extends PrOps[PathTask, O](self)

  sealed trait MoveSemantics
  case object Overwrite extends MoveSemantics
  case object FailIfExists extends MoveSemantics

  trait PathNodeType
  final case object Mount extends PathNodeType
  final case object Plain extends PathNodeType

  final case class FilesystemNode(path: Path, typ: PathNodeType)

  implicit val FilesystemNodeOrder: scala.Ordering[FilesystemNode] =
    scala.Ordering[Path].on(_.path)

  sealed trait TestResult {
    def log: Cord
  }
  object TestResult {
    final case class Success(log: Cord) extends TestResult
    final case class Failure(error: EnvironmentError, log: Cord)
        extends TestResult
    final case class Error(error: Throwable, log: Cord) extends TestResult
  }
  def test(config: BackendConfig): Task[TestResult] = {
    val tests = for {
      backend <- BackendDefinitions.All(config).fold[ETask[EnvironmentError, Backend]](
        EitherT.left(Task.now(MissingBackend("no backend in config: " + config))))(
        EitherT.right(_))
      _     <- backend.checkCompatibility
      paths <- backend.ls.leftMap(EnvPathError(_))

      // TODO:
      // tmp = generate temp Path
      // fs.exists(tmp)  // should be false
      // fs.save(tmp, valuesProcess)
      // fs.scan(tmp, None, None)
      // fs.delete(tmp)

    } yield Cord.mkCord(Cord("\n"), (Cord("Found files:") :: paths.toList.map(p => Cord(p.toString))): _*)

    tests.run.attempt.map(_.fold(
      TestResult.Error(_, ""),
      _.fold(TestResult.Failure(_, ""), TestResult.Success)))
  }
}

/**
  Multi-mount backend that delegates each request to a single mount.
  Any request that references paths in more than one mount will fail.
*/
final case class NestedBackend(sourceMounts: Map[DirNode, Backend]) extends Backend {
  import Backend._
  import CompilationError._
  import EvaluationError._
  import PathError._
  import ProcessingError._

  // We use a var because we can’t leave the user with a copy that has a
  // reference to a concrete backend that no longer exists.
  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.Var"))
  private var mounts = sourceMounts

  private def nodePath(node: DirNode) = Path(List(DirNode.Current, node), None)

  def checkCompatibility: ETask[EnvironmentError, Unit] =
    mounts.values.toList.map(_.checkCompatibility).sequenceU.map(κ(()))

  def run0(req: QueryRequest): (Vector[PhaseResult], ETask[EvaluationError, ResultPath]) = {
    mounts.map { case (mountDir, backend) =>
      val mountPath = nodePath(mountDir)
      (for {
        q <- slamdata.engine.sql.SQLParser.mapPathsE(req.query, _.rebase(mountPath))
        out <- req.out.map(_.rebase(mountPath).map(Some(_))).getOrElse(\/-(None))
      } yield (backend, mountPath, QueryRequest(q, out, req.variables))).toOption
    }.toList.flatten match {
      case (backend, mountPath, req) :: Nil =>
        val (phases, t) = backend.run0(req)
        phases -> t.map {
          case ResultPath.User(path) => ResultPath.User(mountPath ++ path)
          case ResultPath.Temp(path) => ResultPath.Temp(mountPath ++ path)
        }
      case Nil =>
        // TODO: Restore this error message when #771 is fixed.
        // val err = InternalPathError("no single backend can handle all paths for request: " + req)
        val err = InvalidPathError("the request either contained a nonexistent path or could not be handled by a single backend: " + req.query)
        (Vector(PhaseResult.Error("Paths", CompilePathError(err))), EitherT.left(Task.now(CompileFailed())))
      case _   =>
        val err = InternalPathError("multiple backends can handle all paths for request: " + req)
        (Vector(PhaseResult.Error("Paths", CompilePathError(err))), EitherT.left(Task.now(CompileFailed())))
    }
  }

  def scan0(path: Path, offset: Long, limit: Option[Long]):
      Process[ETask[ResultError, ?], Data] =
    delegateP(path)(_.scan0(_, offset, limit), ResultPathError)

  def count0(path: Path): PathTask[Long] = delegate(path)(_.count0(_), ɩ)

  def save0(path: Path, values: Process[Task, Data]):
      ProcessingTask[Unit] =
    delegate(path)(_.save(_, values), PPathError(_))

  def append0(path: Path, values: Process[Task, Data]):
      Process[PathTask, WriteError] =
    delegateP(path)(_.append0(_, values), ɩ)

  def move0(src: Path, dst: Path, semantics: Backend.MoveSemantics): PathTask[Unit] =
    delegate(src)((srcBackend, srcPath) => delegate(dst)((dstBackend, dstPath) =>
      if (srcBackend == dstBackend)
        srcBackend.move0(srcPath, dstPath, semantics)
      else
        EitherT.left(Task.now(InternalPathError("src and dst path not in the same backend"))),
      ɩ),
    ɩ)

  def delete0(path: Path): PathTask[Unit] = path.dir match {
    case Nil =>
      path.file.fold(
        // delete all children because a parent (and us) was deleted
        mounts.toList.map(_._2.delete0(path)).sequenceU.map(_.concatenate))(
        κ(EitherT.left(Task.now(NonexistentPathError(path, None)))))
    case last :: Nil => for {
      _ <- delegate(path)(_.delete0(_), ɩ)
      _ <- EitherT.right(path.file.fold(
        Task.delay{ mounts = mounts - last })(
        κ(().point[Task])))
    } yield ()
    case _ => delegate(path)(_.delete0(_), ɩ)
  }

  def ls0(dir: Path): PathTask[Set[FilesystemNode]] =
    if (dir == Path.Current)
      EitherT.right(Task.now(mounts.toSet[(DirNode, Backend)].map { case (d, b) =>
        FilesystemNode(nodePath(d), b match {
          case NestedBackend(_) => Plain
          case _                => Mount
        })
      }))
      else delegate(dir)(_.ls0(_), ɩ)

  def defaultPath = Path.Current

  private def delegate[E, A](path: Path)(f: (Backend, Path) => ETask[E, A], ef: PathError => E): ETask[E, A] =
    path.asAbsolute.dir.headOption.fold[ETask[E, A]](
      EitherT.left(Task.now(ef(NonexistentPathError(path, None)))))(
      node => mounts.get(node).fold(
        EitherT.left[Task, E, A](Task.now(ef(NonexistentPathError(path, None)))))(
        b => EitherT(Task.now(path.rebase(nodePath(node)).leftMap(ef))).flatMap(f(b, _))))

  private def delegateP[E, A](path: Path)(f: (Backend, Path) => Process[ETask[E, ?], A], ef: PathError => E): Process[ETask[E, ?], A] =
    path.asAbsolute.dir.headOption.fold[Process[ETask[E, ?], A]](
      Process.eval[ETask[E, ?], A](EitherT.left(Task.now(ef(NonexistentPathError(path, None))))))(
      node => mounts.get(node).fold(
        Process.eval[ETask[E, ?], A](EitherT.left(Task.now(ef(NonexistentPathError(path, None))))))(
        b => Process.eval[ETask[E, ?], Path](EitherT(Task.now(path.rebase(nodePath(node)).leftMap(ef)))).flatMap[ETask[E, ?], A](f(b, _))))
}

final case class BackendDefinition(create: PartialFunction[BackendConfig, Task[Backend]]) extends (BackendConfig => Option[Task[Backend]]) {
  def apply(config: BackendConfig): Option[Task[Backend]] = create.lift(config)
}

object BackendDefinition {
  implicit val BackendDefinitionMonoid = new Monoid[BackendDefinition] {
    def zero = BackendDefinition(PartialFunction.empty)

    def append(v1: BackendDefinition, v2: => BackendDefinition): BackendDefinition =
      BackendDefinition(v1.create.orElse(v2.create))
  }
}
