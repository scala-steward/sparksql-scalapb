package scalapb.spark

import org.apache.spark.sql.catalyst.expressions.objects.{
  Invoke,
  MapObjects,
  StaticInvoke
}
import org.apache.spark.sql.catalyst.expressions.{
  CreateNamedStruct,
  Expression,
  If,
  IsNull,
  Literal
}
import org.apache.spark.sql.types.{BooleanType, IntegerType, ObjectType}
import scalapb.descriptors.{Descriptor, FieldDescriptor, PValue, ScalaType}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

object ToCatalystHelpers {
  def messageToCatalyst(
      cmp: GeneratedMessageCompanion[_],
      input: Expression
  ): Expression = {
    val nameExprs = cmp.scalaDescriptor.fields.map { field =>
      Literal(field.name)
    }

    val valueExprs = cmp.scalaDescriptor.fields.map { field =>
      ToCatalystHelpers.fieldToCatalyst(cmp, field, input)
    }

    // the way exprs are encoded in CreateNamedStruct
    val exprs = nameExprs.zip(valueExprs).flatMap {
      case (nameExpr, valueExpr) => nameExpr :: valueExpr :: Nil
    }

    val createExpr = CreateNamedStruct(exprs)
    val nullExpr = Literal.create(null, createExpr.dataType)
    If(IsNull(input), nullExpr, createExpr)
  }

  def fieldToCatalyst(
      cmp: GeneratedMessageCompanion[_],
      fd: FieldDescriptor,
      inputObject: Expression
  ): Expression = {
    val getField = Invoke(
      inputObject,
      "getField",
      ObjectType(classOf[PValue]),
      Invoke(
        Invoke(
          Invoke(
            Literal.fromObject(cmp),
            "scalaDescriptor",
            ObjectType(classOf[Descriptor]),
            Nil
          ),
          "findFieldByNumber",
          ObjectType(classOf[Option[_]]),
          Literal(fd.number) :: Nil
        ),
        "get",
        ObjectType(classOf[FieldDescriptor])
      ) :: Nil
    )

    val getFieldByNumber = Invoke(
      inputObject,
      "getFieldByNumber",
      if (fd.isRepeated)
        ObjectType(classOf[Seq[_]])
      else
        ObjectType(classOf[GeneratedMessage]),
      Literal(fd.number, IntegerType) :: Nil
    )

    val isMessage = fd.scalaType.isInstanceOf[ScalaType.Message]

    val (fieldGetter, transform): (Expression, Expression => Expression) =
      if (!isMessage) {
        (getField, { e: Expression =>
          singularFieldToCatalyst(fd, e)
        })
      } else {
        val subCmp = cmp.messageCompanionForFieldNumber(fd.number)
        (getFieldByNumber, { e: Expression =>
          messageToCatalyst(subCmp, e)
        })
      }

    if (fd.isRepeated) {
      if (isMessage)
        MapObjects(
          transform,
          fieldGetter,
          ObjectType(classOf[GeneratedMessage])
        )
      else {
        val getter = StaticInvoke(
          JavaHelpers.getClass,
          ObjectType(classOf[Vector[_]]),
          "vectorFromPValue",
          fieldGetter :: Nil
        )
        MapObjects(transform, getter, ObjectType(classOf[PValue]))
      }
    } else {
      if (isMessage) transform(fieldGetter)
      else
        If(
          StaticInvoke(
            JavaHelpers.getClass,
            BooleanType,
            "isEmpty",
            getField :: Nil
          ),
          Literal.create(null, ProtoSQL.dataTypeFor(fd)),
          transform(fieldGetter)
        )
    }
  }

  def singularFieldToCatalyst(
      fd: FieldDescriptor,
      input: Expression
  ): Expression = {
    val obj = fd.scalaType match {
      case ScalaType.Int        => "intFromPValue"
      case ScalaType.Long       => "longFromPValue"
      case ScalaType.Float      => "floatFromPValue"
      case ScalaType.Double     => "doubleFromPValue"
      case ScalaType.Boolean    => "booleanFromPValue"
      case ScalaType.String     => "stringFromPValue"
      case ScalaType.ByteString => "byteStringFromPValue"
      case ScalaType.Enum(_)    => "enumFromPValue"
      case ScalaType.Message(_) =>
        throw new RuntimeException("Should not happen")
    }
    StaticInvoke(
      JavaHelpers.getClass,
      ProtoSQL.singularDataType(fd),
      obj,
      input :: Nil
    )
  }
}
