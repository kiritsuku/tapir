package tapir.docs.openapi.schema

import tapir.docs.openapi.uniqueName
import tapir.openapi.OpenAPI.ReferenceOr
import tapir.openapi.{Schema => OSchema}
import tapir.{Schema => TSchema, _}

import scala.collection.immutable
import scala.collection.immutable.ListMap

object ObjectSchemasForEndpoints {

  def apply(es: Iterable[Endpoint[_, _, _, _]]): (ListMap[SchemaKey, ReferenceOr[OSchema]], ObjectSchemas) = {
    val sObjects = es.flatMap(e => forInput(e.input) ++ forOutput(e.errorOutput) ++ forOutput(e.output))
    val infoToKey = calculateUniqueKeys(sObjects.map(_._1.info))
    val schemaReferences = new SchemaReferenceMapper(infoToKey)
    val discriminatorToOpenApi = new DiscriminatorToOpenApi(schemaReferences)
    val tschemaToOSchema = new TSchemaToOSchema(schemaReferences, discriminatorToOpenApi)
    val schemas = new ObjectSchemas(tschemaToOSchema, schemaReferences)
    val infosToSchema = sObjects.map(so => (so._1.info, tschemaToOSchema(so))).toMap

    val schemaKeys = infosToSchema.map { case (k, v) => k -> ((infoToKey(k), v)) }
    (schemaKeys.values.toListMap, schemas)
  }

  private def calculateUniqueKeys(infos: Iterable[TSchema.SObjectInfo]): Map[TSchema.SObjectInfo, SchemaKey] = {
    case class SchemaKeyAssignment1(keyToInfo: Map[SchemaKey, TSchema.SObjectInfo], infoToKey: Map[TSchema.SObjectInfo, SchemaKey])
    infos
      .foldLeft(SchemaKeyAssignment1(Map.empty, Map.empty)) {
        case (SchemaKeyAssignment1(keyToInfo, infoToKey), objectInfo) =>
          val key = uniqueName(objectInfoToName(objectInfo), n => !keyToInfo.contains(n) || keyToInfo.get(n).contains(objectInfo))

          SchemaKeyAssignment1(
            keyToInfo + (key -> objectInfo),
            infoToKey + (objectInfo -> key)
          )
      }
      .infoToKey
  }

  private def objectSchemas(schema: TSchema, validator: Validator[_]): List[(TSchema.SObject, Validator[_])] = {
    (schema, validator) match {
      case (p: TSchema.SProduct, v) =>
        List(p -> v) ++ schemaWithValidatorForFields(p, v)
          .flatMap(k => objectSchemas(k._1, k._2))
          .toList
      case (TSchema.SArray(o), v) =>
        objectSchemas(o, v)
      case (s: TSchema.SCoproduct, v) =>
        (s -> v) +: s.schemas.flatMap(c => objectSchemas(c, Validator.passing)).toList
      case _ => List.empty
    }
  }

  private def schemaWithValidatorForFields(p: TSchema.SProduct, v: Validator[_]): immutable.Seq[(TSchema, Validator[_])] = {
    v match {
      case ProductValidator(fields) =>
        p.fields.map { f =>
          f._2 -> fields(f._1).validator
        }.toList
      case _ => p.fields.map(f => f._2 -> Validator.passing[f.type]).toList
    }
  }

  private def forInput(input: EndpointInput[_]): List[(TSchema.SObject, Validator[_])] = {
    input match {
      case EndpointInput.FixedMethod(_) =>
        List.empty
      case EndpointInput.FixedPath(_) =>
        List.empty
      case EndpointInput.PathCapture(tm, _, _) =>
        objectSchemas(tm.meta.schema, tm.validator)
      case EndpointInput.PathsCapture(_) =>
        List.empty
      case EndpointInput.Query(_, tm, _) =>
        objectSchemas(tm.meta.schema, tm.validator)
      case EndpointInput.Cookie(_, tm, _) =>
        objectSchemas(tm.meta.schema, tm.validator)
      case EndpointInput.QueryParams(_) =>
        List.empty
      case _: EndpointInput.Auth[_] =>
        List.empty
      case _: EndpointInput.ExtractFromRequest[_] =>
        List.empty
      case EndpointInput.Mapped(wrapped, _, _, _) =>
        forInput(wrapped)
      case EndpointInput.Multiple(inputs) =>
        inputs.toList.flatMap(forInput)
      case op: EndpointIO[_] => forIO(op)
    }
  }

  private def forOutput(output: EndpointOutput[_]): List[(TSchema.SObject, Validator[_])] = {
    output match {
      case EndpointOutput.OneOf(mappings) =>
        mappings.flatMap(mapping => forOutput(mapping.output)).toList
      case EndpointOutput.StatusCode() =>
        List.empty
      case EndpointOutput.FixedStatusCode(_, _) =>
        List.empty
      case EndpointOutput.Mapped(wrapped, _, _, _) =>
        forOutput(wrapped)
      case EndpointOutput.Multiple(outputs) =>
        outputs.toList.flatMap(forOutput)
      case op: EndpointIO[_] => forIO(op)
    }
  }

  private def forIO(io: EndpointIO[_]): List[(TSchema.SObject, Validator[_])] = {
    io match {
      case EndpointIO.Multiple(ios) =>
        ios.toList.flatMap(ios2 => forInput(ios2) ++ forOutput(ios2))
      case EndpointIO.Header(_, tm, _) =>
        objectSchemas(tm.meta.schema, tm.validator)
      case EndpointIO.Headers(_) =>
        List.empty
      case EndpointIO.Body(tm, _) =>
        objectSchemas(tm.meta.schema, tm.validator)
      case EndpointIO.StreamBodyWrapper(StreamingEndpointIO.Body(schema, _, _)) =>
        objectSchemas(schema, Validator.passing)
      case EndpointIO.Mapped(wrapped, _, _, _) =>
        forInput(wrapped) ++ forOutput(wrapped)
    }
  }

  private def objectInfoToName(info: TSchema.SObjectInfo): String = {
    val lastDotIndex = info.fullName.lastIndexOf('.')
    val shortName = if (lastDotIndex == -1) {
      info.fullName
    } else {
      info.fullName.substring(lastDotIndex + 1)
    }

    val typeParams = if (info.typeParameterShortNames.nonEmpty) {
      "_" + info.typeParameterShortNames.mkString("_")
    } else {
      ""
    }

    shortName + typeParams
  }
}
