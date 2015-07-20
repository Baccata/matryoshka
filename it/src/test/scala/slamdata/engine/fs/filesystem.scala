package slamdata.engine.physical.mongodb

import org.specs2.execute.{Result}

import scala.collection.immutable.ListMap

import scalaz._; import Scalaz._
import scalaz.concurrent._
import scalaz.stream._

import slamdata.engine._
import slamdata.engine.fp._
import slamdata.engine.fs._

class FileSystemSpecs extends BackendTest with slamdata.engine.DisjunctionMatchers {
  import Backend._
  import Errors._
  import PathError._
  import ProcessingError._
  import slamdata.engine.fs._

  def oneDoc: Process[Task, Data] =
    Process.emit(Data.Obj(ListMap("a" -> Data.Int(1))))
  def anotherDoc: Process[Task, Data] =
    Process.emit(Data.Obj(ListMap("b" -> Data.Int(2))))

  tests {  case (backendName, fs) =>
    val TestDir = testRootDir(fs) ++ genTempDir.run

    backendName should {

      "FileSystem" should {
        // Run the task to create a single FileSystem instance for each run (I guess)

        "list root" in {
          fs.ls(Path(".")).map(_ must contain(FilesystemNode(fs.defaultPath, Plain))).run.run must beAnyRightDisj
        }

        "have zips" in {
          // This is the collection we use for all of our examples, so might as well make sure it's there.
          fs.ls(fs.defaultPath).map(_ must contain(FilesystemNode(Path("./zips"), Plain))).run.run must beAnyRightDisj
          fs.count(fs.defaultPath ++ Path("zips")).run.run must beRightDisj(29353L)
        }

        "read zips with skip and limit" in {
          (for {
            cursor <- fs.scan(fs.defaultPath ++ Path("zips"), 100, Some(5)).runLog
            process <- fs.scan(fs.defaultPath ++ Path("zips"), 0, None).drop(100).take(5).runLog
          } yield {
            cursor must_== process
          }).fold(_ must beNull, ɩ).run
        }

        "fail when reading zips with negative skip and zero limit" in {
          fs.scan(fs.defaultPath ++ Path("zips"), -1, None).run.fold(_ must beNull, ɩ).attemptRun must beAnyLeftDisj
          fs.scan(fs.defaultPath ++ Path("zips"), 0, Some(0)).run.fold(_ must beNull, ɩ).attemptRun must beAnyLeftDisj
        }

        "save one" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            before <- fs.ls(TestDir).leftMap(PPathError(_))
            _      <- fs.save(TestDir ++ tmp, oneDoc)
            after  <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            before must not(contain(FilesystemNode(tmp, Plain)))
            after must contain(FilesystemNode(tmp, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "allow duplicate saves" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            _      <- fs.save(TestDir ++ tmp, oneDoc)
            before <- fs.ls(TestDir).leftMap(PPathError(_))
            _      <- fs.save(TestDir ++ tmp, oneDoc)
            after  <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            before must contain(FilesystemNode(tmp, Plain))
            after must contain(FilesystemNode(tmp, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "fail duplicate creates" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            _      <- fs.create(TestDir ++ tmp, oneDoc)
            before <- fs.ls(TestDir).leftMap(PPathError(_))
            rez    <- liftE[ProcessingError](fs.create(TestDir ++ tmp, anotherDoc).run)
            after  <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must_== before
            rez must beLeftDisj(PPathError(ExistingPathError(TestDir ++ tmp, Some("can’t be created, because it already exists"))))
          }).fold(_ must beNull, ɩ).run
        }

        "fail initial replace" in {
          (for {
            tmp    <- liftP(genTempFile)
            before <- fs.ls(TestDir)
            rez    <- liftP(fs.replace(TestDir ++ tmp, anotherDoc).run)
            after  <- fs.ls(TestDir)
          } yield {
            after must_== before
            rez must beLeftDisj(PPathError(NonexistentPathError(TestDir ++ tmp, Some("can’t be replaced, because it doesn’t exist"))))
          }).fold(_ must beNull, ɩ).run
        }

        "replace one" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            _      <- fs.create(TestDir ++ tmp, oneDoc)
            before <- fs.ls(TestDir).leftMap(PPathError(_))
            _      <- fs.replace(TestDir ++ tmp, anotherDoc)
            after  <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            before must contain(FilesystemNode(tmp, Plain))
            after must contain(FilesystemNode(tmp, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "save one (subdir)" in {
          (for {
            tmpDir <- liftE[ProcessingError](genTempDir)
            tmp = Path("file1")
            before <- fs.ls(TestDir ++ tmpDir).leftMap(PPathError(_))
            _      <- fs.save(TestDir ++ tmpDir ++ tmp, oneDoc)
            after  <- fs.ls(TestDir ++ tmpDir).leftMap(PPathError(_))
          } yield {
            before must not(contain(FilesystemNode(tmp, Plain)))
            after must contain(FilesystemNode(tmp, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "save one with error" in {
          val badJson = Data.Int(1)
          val data: Process[Task, Data] = Process.emit(badJson)
          (for {
            tmpDir <- liftP(genTempDir)
            file = tmpDir ++ Path("file1")
            before <- fs.ls(TestDir ++ tmpDir)
            rez    <- liftP(fs.save(TestDir ++ file, data).run)
            after  <- fs.ls(TestDir ++ tmpDir)
          } yield {
            rez must beAnyLeftDisj
            after must_== before
          }).fold(_ must beNull, ɩ).run
        }

        "save many (approx. 10 MB in 1K docs)" in {
          val sizeInMB = 10.0

          // About 0.5K each of data, and 0.25K of index, etc.:
          def jsonTree(depth: Int): Data =
            if (depth == 0) Data.Arr(Data.Str("abc") :: Data.Int(123) :: Data.Str("do, re, mi") :: Nil)
            else            Data.Obj(ListMap("left" -> jsonTree(depth-1), "right" -> jsonTree(depth-1)))
          def json(i: Int) = Data.Obj(ListMap("seq" -> Data.Int(i), "filler" -> jsonTree(3)))

          // This is _very_ approximate:
          val bytesPerDoc = 750
          val count = (sizeInMB*1024*1024/bytesPerDoc).toInt

          val data: Process[Task, Data] = Process.emitAll(0 until count).map(json(_))

          (for {
            tmp   <- liftE[ProcessingError](genTempFile)
            _     <- fs.save(TestDir ++ tmp, data)
            after <- fs.ls(TestDir).leftMap(PPathError(_))
            _     <- fs.delete(TestDir ++ tmp).leftMap(PPathError(_)) // clean up this one eagerly, since it's a large file
          } yield {
            after must contain(FilesystemNode(tmp, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "append one" in {
          val json = Data.Obj(ListMap("a" ->Data.Int(1)))
          val data: Process[Task, Data] = Process.emit(json)
          (for {
            tmp   <- liftE[ProcessingError](genTempFile)
            rez   <- fs.append(TestDir ++ tmp, data).runLog.leftMap(PPathError(_))
            saved <- fs.scan(TestDir ++ tmp, 0, None).runLog.leftMap(PResultError(_))
          } yield {
            rez.size must_== 0
            saved.size must_== 1
          }).fold(_ must beNull, ɩ).run
        }

        "append with one ok and one error" in {
          val json1 = Data.Obj(ListMap("a" ->Data.Int(1)))
          val json2 = Data.Int(1)
          val data: Process[Task, Data] = Process.emitAll(json1 :: json2 :: Nil)
          (for {
            tmp   <- liftE[ProcessingError](genTempFile)
            rez   <- fs.append(TestDir ++ tmp, data).runLog.leftMap(PPathError(_))
            saved <- fs.scan(TestDir ++ tmp, 0, None).runLog.leftMap(PResultError(_))
          } yield {
            rez.size must_== 1
            saved.size must_== 1
          }).fold(_ must beNull, ɩ).run
        }

        "move file" in {
          (for {
            tmp1  <- liftE[ProcessingError](genTempFile)
            tmp2  <- liftE(genTempFile)
            _     <- fs.save(TestDir ++ tmp1, oneDoc)
            _     <- fs.move(TestDir ++ tmp1, TestDir ++ tmp2, FailIfExists).leftMap(PPathError(_))
            after <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmp1, Plain)))
            after must contain(FilesystemNode(tmp2, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "error: move file to existing path" in {
          (for {
            tmp1  <- liftE[ProcessingError](genTempFile)
            tmp2  <- liftE(genTempFile)
            _     <- fs.save(TestDir ++ tmp1, oneDoc)
            _     <- fs.save(TestDir ++ tmp2, oneDoc)
            rez   <- fs.move(TestDir ++ tmp1, TestDir ++ tmp2, FailIfExists).leftMap(PPathError(_)).attempt
            after <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            rez must beAnyLeftDisj
            after must contain(FilesystemNode(tmp1, Plain))
            after must contain(FilesystemNode(tmp2, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "move file to existing path with Overwrite semantics" in {
          (for {
            tmp1  <- liftE[ProcessingError](genTempFile)
            tmp2  <- liftE(genTempFile)
            _     <- fs.save(TestDir ++ tmp1, oneDoc)
            _     <- fs.save(TestDir ++ tmp2, oneDoc)
            _     <- fs.move(TestDir ++ tmp1, TestDir ++ tmp2, Overwrite).leftMap(PPathError(_))
            after <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmp1, Plain)))
            after must contain(FilesystemNode(tmp2, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "move file to itself (NOP)" in {
          (for {
            tmp1  <- liftE[ProcessingError](genTempFile)
            _     <- fs.save(TestDir ++ tmp1, oneDoc)
            _     <- fs.move(TestDir ++ tmp1, TestDir ++ tmp1, FailIfExists).leftMap(PPathError(_))
            after <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must contain(FilesystemNode(tmp1, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "move dir" in {
          (for {
            tmpDir1 <- liftE[ProcessingError](genTempDir)
            tmp1 = tmpDir1 ++ Path("file1")
            tmp2 = tmpDir1 ++ Path("file2")
            _       <- fs.save(TestDir ++ tmp1, oneDoc)
            _       <- fs.save(TestDir ++ tmp2, oneDoc)
            tmpDir2 <- liftE(genTempDir)
            _       <- fs.move(TestDir ++ tmpDir1, TestDir ++ tmpDir2, FailIfExists).leftMap(PPathError(_))
            after   <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmpDir1, Plain)))
            after must contain(FilesystemNode(tmpDir2, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "move dir with destination given as file path" in {
          (for {
            tmpDir1 <- liftE[ProcessingError](genTempDir)
            tmp1 = tmpDir1 ++ Path("file1")
            tmp2 = tmpDir1 ++ Path("file2")
            _       <- fs.save(TestDir ++ tmp1, oneDoc)
            _       <- fs.save(TestDir ++ tmp2, oneDoc)
            tmpDir2 <- liftE(genTempFile)
            _       <- fs.move(TestDir ++ tmpDir1, TestDir ++ tmpDir2, FailIfExists).leftMap(PPathError(_))
            after   <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmpDir1, Plain)))
            after must contain(FilesystemNode(tmpDir2.asDir, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "move missing dir to new (also missing) location (NOP)" in {
          (for {
            tmpDir1 <- liftE[ProcessingError](genTempDir)
            tmpDir2 <- liftE(genTempDir)
            _       <- fs.move(TestDir ++ tmpDir1, TestDir ++ tmpDir2, FailIfExists).leftMap(PPathError(_))
            after   <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmpDir1, Plain)))
            after must not(contain(FilesystemNode(tmpDir2, Plain)))
          }).fold(_ must beNull, ɩ).run
        }

        "delete file" in {
          (for {
            tmp   <- liftE[ProcessingError](genTempFile)
            _     <- fs.save(TestDir ++ tmp, oneDoc)
            _     <- fs.delete(TestDir ++ tmp).leftMap(PPathError(_))
            after <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmp, Plain)))
          }).fold(_ must beNull, ɩ).run
        }

        "delete file but not sibling" in {
          val tmp1 = Path("file1")
          val tmp2 = Path("file2")
          (for {
            tmpDir <- liftE[ProcessingError](genTempDir)
            _      <- fs.save(TestDir ++ tmpDir ++ tmp1, oneDoc)
            _      <- fs.save(TestDir ++ tmpDir ++ tmp2, oneDoc)
            before <- fs.ls(TestDir ++ tmpDir).leftMap(PPathError(_))
            _      <- fs.delete(TestDir ++ tmpDir ++ tmp1).leftMap(PPathError(_))
            after  <- fs.ls(TestDir ++ tmpDir).leftMap(PPathError(_))
          } yield {
            before must contain(FilesystemNode(tmp1, Plain))
            after must not(contain(FilesystemNode(tmp1, Plain)))
            after must contain(FilesystemNode(tmp2, Plain))
          }).fold(_ must beNull, ɩ).run
        }

        "delete dir" in {
          (for {
            tmpDir <- liftE[ProcessingError](genTempDir)
            tmp1 = tmpDir ++ Path("file1")
            tmp2 = tmpDir ++ Path("file2")
            _      <- fs.save(TestDir ++ tmp1, oneDoc)
            _      <- fs.save(TestDir ++ tmp2, oneDoc)
            _      <- fs.delete(TestDir ++ tmpDir).leftMap(PPathError(_))
            after  <- fs.ls(TestDir).leftMap(PPathError(_))
          } yield {
            after must not(contain(FilesystemNode(tmpDir, Plain)))
          }).fold(_ must beNull, ɩ).run
        }

        "delete missing file (not an error)" in {
          (for {
            tmp <- genTempFile
            rez <- fs.delete(TestDir ++ tmp).run.attempt
          } yield {
            rez must beAnyRightDisj
          }).run
        }
      }

      "query evaluation" should {
        import slamdata.engine.sql.{Query, SQLParser}
        import slamdata.engine.{QueryRequest, Variables}

        def parse(query: String) =
          liftE[ProcessingError](SQLParser.parseInContext(Query(query), TestDir).fold(e => Task.fail(new RuntimeException(e.message)), Task.now))

        "leave no temps behind" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            _      <- fs.save(TestDir ++ tmp, oneDoc)

            before <- fs.lsAll(Path.Root).leftMap(PPathError(_))

            // NB: this query *does* produce a temporary result (not a simple read)
            expr   <- parse("select a from " + tmp.simplePathname)
            rez    <- fs.eval(QueryRequest(expr, None, Variables(Map())))._2.runLog

            after  <- fs.lsAll(Path.Root).leftMap(PPathError(_))
          } yield {
            rez must_== Vector(Data.Obj(ListMap("a" -> Data.Int(1))))
            after must contain(exactly(before.toList: _*))
          }).fold(_ must beNull, ɩ).run
        }

        "leave only the output behind" in {
          (for {
            tmp    <- liftE[ProcessingError](genTempFile)
            _      <- fs.save(TestDir ++ tmp, oneDoc)

            before <- fs.lsAll(Path.Root).leftMap(PPathError(_))

            out    <- liftE(genTempFile)
            // NB: this query *does* produce a temporary result (not a simple read)
            expr   <- parse("select a from " + tmp.simplePathname)
            rez    <- fs.eval(QueryRequest(expr, Some(TestDir ++ out), Variables(Map())))._2.runLog

            after  <- fs.lsAll(Path.Root).leftMap(PPathError(_))
          } yield {
            rez must_== Vector(Data.Obj(ListMap("a" -> Data.Int(1))))
            after must contain(exactly(FilesystemNode(TestDir ++ out, Plain) :: before.toList: _*))
          }).fold(_ must beNull, ɩ).run
        }
      }
    }

    val cleanup = step {
      deleteTempFiles(fs, TestDir).run
    }
  }
}
