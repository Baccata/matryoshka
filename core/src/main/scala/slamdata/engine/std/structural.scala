package slamdata.engine.std

import scalaz._
import Scalaz._
import Validation.{success, failure}
import NonEmptyList.nel

import slamdata.engine._

import SemanticError._

trait StructuralLib extends Library {
  import Type._

  val MakeObject = Mapping("MAKE_OBJECT", "Makes a singleton object containing a single field", Str :: Top :: Nil, partialTyper {
    case Const(Data.Str(name)) :: Const(data) :: Nil => Const(Data.Obj(Map(name -> data)))
    case Const(Data.Str(name)) :: (valueType) :: Nil => (NamedField(name, valueType))
    case _ :: Const(data) :: Nil => (AnonField(data.dataType))
    case _ :: valueType :: Nil => (AnonField(valueType))
  }, {
    case Const(Data.Obj(map)) => map.head match { case (key, value) => success(Const(Data.Str(key)) :: Const(value) :: Nil) }
    case NamedField(name, valueType) => success(Const(Data.Str(name)) :: valueType :: Nil)
    case AnonField(tpe) => success(Str :: tpe :: Nil)
    case t => failure(nel(TypeError(AnyObject, t), Nil))
  })

  val MakeArray = Mapping("MAKE_ARRAY", "Makes a singleton array containing a single element", Top :: Nil, partialTyper {
    case Const(data) :: Nil => Const(Data.Arr(data :: Nil))
    case (valueType) :: Nil => (AnonElem(valueType))
    case _ => AnyArray
  }, {
    case Const(Data.Arr(arr)) => success(Const(arr.head) :: Nil)
    case AnonElem(elemType) => success(elemType :: Nil)
    case t => failure(nel(TypeError(AnyArray, t), Nil))
  })

  val ObjectConcat = Mapping("OBJECT_CONCAT", "A right-biased merge of two objects into one object", AnyObject :: AnyObject :: Nil, partialTyper {
    case Const(Data.Obj(map1)) :: Const(Data.Obj(map2)) :: Nil => Const(Data.Obj(map1 ++ map2))
    case v1 :: v2 :: Nil => (v1 & v2)
  }, {
    case x if x.objectLike => success(AnyObject :: AnyObject :: Nil)
    case x => failure(nel(TypeError(AnyObject, x), Nil))
  })

  val ArrayConcat = Mapping("ARRAY_CONCAT", "A right-biased merge of two arrays into one array", AnyArray :: AnyArray :: Nil, partialTyper {
    case Const(Data.Arr(els1)) :: Const(Data.Arr(els2)) :: Nil => Const(Data.Arr(els1 ++ els2))
    case v1 :: v2 :: Nil => (v1 & v2) // TODO: Unify het array into hom array
  }, {
    case x if x.arrayLike => success(AnyArray :: AnyArray :: Nil)
    case x => failure(nel(TypeError(AnyArray, x), Nil))
  })

  // NB: Used only during type-checking, and then compiled into either (string) Concat or ArrayConcat.
  val ConcatOp = Mapping("(||)", "A right-biased merge of two arrays/strings.", (AnyArray | Str) :: (AnyArray | Str) :: Nil, partialTyperV {
    case Const(Data.Arr(els1)) :: Const(Data.Arr(els2)) :: Nil     => success(Const(Data.Arr(els1 ++ els2)))
    case t1 :: t2 :: Nil if (t1.arrayLike) && (t2 contains Top)    => success(t1 & AnonElem(Top))
    case t1 :: t2 :: Nil if (t1 contains Top) && (t2.arrayLike)    => success(AnonElem(Top) & t2)
    case t1 :: t2 :: Nil if (t1.arrayLike) && (t2.arrayLike)       => success(t1 & t2)  // TODO: Unify het array into hom array

    case Const(Data.Str(str1)) :: Const(Data.Str(str2)) :: Nil     => success(Const(Data.Str(str1 ++ str2)))
    case t1 :: t2 :: Nil if (Str contains t1) && (t2 contains Top) => success(Type.Str)
    case t1 :: t2 :: Nil if (t1 contains Top) && (Str contains t2) => success(Type.Str)
    case t1 :: t2 :: Nil if (Str contains t1) && (Str contains t2) => success(Type.Str)

    case t1 :: t2 :: Nil if t1 == t2 => success(t1)

    case t1 :: t2 :: Nil if (Str contains t1) && (t2.arrayLike) => failure(NonEmptyList(GenericError("cannot concat string with array")))
    case t1 :: t2 :: Nil if (t1.arrayLike) && (Str contains t2) => failure(NonEmptyList(GenericError("cannot concat array with string")))
  }, {
    case x if x contains (AnyArray | Str) => success((AnyArray | Str) :: (AnyArray | Str) :: Nil)
    case x if x.arrayLike => success(AnyArray :: AnyArray :: Nil)
    case Type.Str => success(Type.Str :: Type.Str :: Nil)
    case x => failure(nel(TypeError(AnyArray | Str, x), Nil))
  })

  val ObjectProject = Mapping("({})", "Extracts a specified field of an object", AnyObject :: Str :: Nil, partialTyperV {
    case v1 :: v2 :: Nil => v1.objectField(v2)
  }, {
    case x => success(AnonField(x) :: Str :: Nil)
  })

  val ArrayProject = Mapping("([])", "Extracts a specified index of an array", AnyArray :: Int :: Nil, partialTyperV {
    case v1 :: v2 :: Nil => v1.arrayElem(v2)
  }, {
    case x => success(AnonElem(x) :: Int :: Nil)
  })

  val DeleteField = Mapping("DELETE_FIELD", "Deletes a specified field from an object",
    AnyObject :: Str :: Nil,
    partialTyperV {
      case v1 :: v2 :: Nil => success(AnyObject) // TODO: remove field from v1 type
    }, {
      case x if x.objectLike => success(AnyObject :: Str :: Nil)
      case x => failure(nel(TypeError(AnyObject, x), Nil))
    })

  val FlattenObject = ExpansionFlat("FLATTEN_OBJECT", "Flattens an object into a set", AnyObject :: Nil, partialTyper {
    case x :: Nil if (!x.objectType.isEmpty) => x.objectType.get
  }, {
    case tpe => success(AnonField(tpe) :: Nil)
  })

  val FlattenArray = ExpansionFlat("FLATTEN_ARRAY", "Flattens an array into a set", AnyArray :: Nil, partialTyper {
    case x :: Nil if (!x.arrayType.isEmpty) => x.arrayType.get
  }, {
    case tpe => success(AnonElem(tpe) :: Nil)
  })

  def functions = MakeObject :: MakeArray ::
                  ObjectConcat :: ArrayConcat :: ConcatOp ::
                  ObjectProject :: ArrayProject ::
                  FlattenObject :: FlattenArray ::
                  Nil

  // TODO: fix types and add the VirtualFuncs to the list of functions

  // val MakeObjectN = new VirtualFunc {
  object MakeObjectN {
    import slamdata.engine.analysis.fixplate._

    // Note: signature does not match VirtualFunc
    def apply(args: (Term[LogicalPlan], Term[LogicalPlan])*): Term[LogicalPlan] =
      args.map(t => Term(MakeObject(t._1, t._2))) match {
        case t :: Nil => t
        case mas => mas.reduce((t, ma) => Term(ObjectConcat(t, ma)))
      }

    // Note: signature does not match VirtualFunc
    def unapply(t: Term[LogicalPlan]): Option[List[(Term[LogicalPlan], Term[LogicalPlan])]] =
      for {
        pairs <- Attr.unapply(attrK(t, ()))
      } yield pairs.map(_.bimap(forget(_), forget(_)))

    object Attr {
      // Note: signature does not match VirtualFuncAttrExtractor
      def unapply[A](t: Cofree[LogicalPlan, A]): Option[List[(Cofree[LogicalPlan, A], Cofree[LogicalPlan, A])]] = t.tail match {
        case MakeObject(name :: expr :: Nil) =>
          Some((name, expr) :: Nil)

        case ObjectConcat(a :: b :: Nil) =>
          (unapply(a) |@| unapply(b))(_ ::: _)

        case _ => None
      }
    }
  }

  val MakeArrayN: VirtualFunc = new VirtualFunc {
    import slamdata.engine.analysis.fixplate._

    def apply(args: Term[LogicalPlan]*): Term[LogicalPlan] =
      args.map(t => Term(MakeArray(t))) match {
        case Nil      => LogicalPlan.Constant(Data.Arr(Nil))
        case t :: Nil => t
        case mas      => mas.reduce((t, ma) => Term(ArrayConcat(t, ma)))
      }

    def Attr = new VirtualFuncAttrExtractor {
      def unapply[A](t: Cofree[LogicalPlan, A]): Option[List[Cofree[LogicalPlan, A]]] = t.tail match {
        case MakeArray(x :: Nil) =>
          Some(x :: Nil)

        case ArrayConcat(a :: b :: Nil) =>
          (unapply(a) |@| unapply(b))(_ ::: _)

        case _ => None
      }
    }
  }
}
object StructuralLib extends StructuralLib
