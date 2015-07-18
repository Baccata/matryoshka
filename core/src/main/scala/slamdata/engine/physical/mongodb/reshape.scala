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

package slamdata.engine.physical.mongodb

import scala.collection.immutable.{ListMap}

import scalaz._
import Scalaz._

import slamdata.engine.{PlannerError, RenderTree, Terminal, NonTerminal, RenderedTree}
import slamdata.engine.fp._
import slamdata.engine.javascript._

final case class Grouped(value: ListMap[BsonField.Leaf, ExprOp.GroupOp]) {
  def bson = Bson.Doc(value.map(t => t._1.asText -> t._2.bson))

  def rewriteRefs(f: PartialFunction[ExprOp.DocVar, ExprOp.DocVar]): Grouped =
    Grouped(value.transform((_, v) => v.rewriteRefs(f)))
}
object Grouped {
  implicit def GroupedRenderTree = new RenderTree[Grouped] {
    val GroupedNodeType = List("Grouped")

    def render(grouped: Grouped) = NonTerminal(GroupedNodeType, None,
                                    (grouped.value.map { case (name, expr) => Terminal("Name" :: GroupedNodeType, Some(name.bson.repr.toString + " -> " + expr.bson.repr.toString)) } ).toList)
  }
}

final case class Reshape(value: ListMap[BsonField.Name, ExprOp \/ Reshape]) {
  import ExprOp._

  def toJs: PlannerError \/ JsFn =
    value.map { case (key, expr) =>
      key.asText -> expr.fold(ExprOp.toJs, _.toJs)
    }.sequenceU.map { l => JsFn(JsFn.base,
      JsCore.Obj(l.map { case (k, v) => k -> v(JsFn.base.fix) }).fix)
    }

  def bson: Bson.Doc = Bson.Doc(value.map {
    case (field, either) => field.asText -> either.fold(_.bson, _.bson)
  })

  private def projectSeq(fs: NonEmptyList[BsonField.Leaf]):
      Option[ExprOp \/ Reshape] =
    fs.foldLeftM[Option, ExprOp \/ Reshape](\/-(this))((rez, leaf) =>
      rez.fold(κ(None), r =>
        leaf match {
          case n @ BsonField.Name(_) => r.get(n)
          case _                     => None
        }))

  def rewriteRefs(applyVar: PartialFunction[ExprOp.DocVar, ExprOp.DocVar]):
      Reshape =
    Reshape(value.transform((k, v) => v.bimap(
      {
        case Include => DocField(k).rewriteRefs(applyVar)
        case x       => x.rewriteRefs(applyVar)
      },
      _.rewriteRefs(applyVar))))

  def \ (f: BsonField): Option[ExprOp \/ Reshape] = projectSeq(f.flatten)

  def get(field: BsonField): Option[ExprOp \/ Reshape] =
    field.flatten.foldLeftM[Option, ExprOp \/ Reshape](
      \/-(this))(
      (rez, elem) => rez.fold(κ(None), _.value.get(elem.toName)))

  def set(field: BsonField, newv: ExprOp \/ Reshape): Reshape = {
    def getOrDefault(o: Option[ExprOp \/ Reshape]): Reshape = {
      o.map(_.fold(κ(Reshape.EmptyDoc), identity)).getOrElse(Reshape.EmptyDoc)
    }

    def set0(cur: Reshape, els: List[BsonField.Leaf]): Reshape = els match {
      case Nil => ??? // TODO: Refactor els to be NonEmptyList
      case (x @ BsonField.Name(_)) :: Nil => Reshape(cur.value + (x -> newv))
      case (x @ BsonField.Index(_)) :: Nil => Reshape(cur.value + (x.toName -> newv))
      case (x @ BsonField.Name(_)) :: xs =>
        Reshape(cur.value + (x -> \/-(set0(getOrDefault(cur.value.get(x)), xs))))
      case (x @ BsonField.Index(_)) :: xs =>
        Reshape(cur.value + (x.toName -> \/-(set0(getOrDefault(cur.value.get(x.toName)), xs))))
    }

    set0(this, field.flatten.toList)
  }
}

object Reshape {
  val EmptyDoc = Reshape(ListMap())

  def getAll(r: Reshape): List[(BsonField, ExprOp)] = {
    def getAll0(f0: BsonField, e: ExprOp \/ Reshape) = e.fold(
      e => (f0 -> e) :: Nil,
      r => getAll(r).map { case (f, e) => (f0 \ f) -> e })

    r.value.toList.map { case (f, e) => getAll0(f, e) }.flatten
  }

  def setAll(r: Reshape, fvs: Iterable[(BsonField, ExprOp \/ Reshape)]) =
    fvs.foldLeft(r) {
      case (r0, (field, value)) => r0.set(field, value)
    }

  def mergeMaps[A, B](lmap: ListMap[A, B], rmap: ListMap[A, B]):
      Option[ListMap[A, B]] =
    if ((lmap.keySet & rmap.keySet).forall(k => lmap.get(k) == rmap.get(k)))
      Some(lmap ++ rmap)
    else None

  def merge(r1: Reshape, r2: Reshape): Option[Reshape] = {
    val lmap = Reshape.getAll(r1).map(t => t._1 -> -\/(t._2)).toListMap
    val rmap = Reshape.getAll(r2).map(t => t._1 -> -\/(t._2)).toListMap
    if ((lmap.keySet & rmap.keySet).forall(k => lmap.get(k) == rmap.get(k)))
      Some(Reshape.setAll(
        r1,
        Reshape.getAll(r2).map(t => t._1 -> -\/ (t._2))))
    else None
  }

  private val ProjectNodeType = List("Project")

  private[mongodb] def renderReshape(shape: Reshape): List[RenderedTree] = {
    def renderField(field: BsonField, value: ExprOp \/ Reshape) = {
      val (label, typ) = field match {
        case BsonField.Index(value) => value.toString -> "Index"
        case _ => field.bson.repr.toString -> "Name"
      }
      value match {
        case -\/  (exprOp) => Terminal(typ :: ProjectNodeType, Some(label + " -> " + exprOp.bson.repr.toString))
        case  \/- (shape)  => NonTerminal(typ :: ProjectNodeType, Some(label), renderReshape(shape))
      }
    }

    shape.value.map((renderField(_, _)).tupled).toList
  }
}
