package sttp.tapir.codec.refined

import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.{Greater, GreaterEqual, Less, LessEqual}
import eu.timepit.refined.refineV
import eu.timepit.refined.string.MatchesRegex
import shapeless.Witness
import sttp.tapir._

import scala.reflect.ClassTag

trait TapirCodecRefined extends LowPriorityValidatorForPredicate {
  implicit def refinedTapirSchema[V, P](implicit
      vSchema: Schema[V],
      refinedValidator: Validate[V, P],
      refinedValidatorTranslation: ValidatorForPredicate[V, P]
  ): Schema[V Refined P] =
    vSchema.validate(refinedValidatorTranslation.validator).map[V Refined P](v => refineV[P](v).toOption)(_.value)

  implicit def codecForRefined[R, V, P, CF <: CodecFormat](implicit
      tm: Codec[R, V, CF],
      refinedValidator: Validate[V, P],
      refinedValidatorTranslation: ValidatorForPredicate[V, P]
  ): Codec[R, V Refined P, CF] = {
    implicitly[Codec[R, V, CF]]
      .validate(
        refinedValidatorTranslation.validator
      ) // in reality if this validator has to fail, it will fail before in mapDecode while trying to construct refined type
      .mapDecode { v: V =>
        refineV[P](v) match {
          case Right(refined) => DecodeResult.Value(refined)
          case Left(errorMessage) =>
            DecodeResult.InvalidValue(refinedValidatorTranslation.validationErrors(v, errorMessage))
        }
      }(_.value)
  }

  //

  implicit val validatorForNonEmptyString: ValidatorForPredicate[String, NonEmpty] =
    ValidatorForPredicate.fromPrimitiveValidator[String, NonEmpty](Validator.minLength(1))

  implicit def validatorForMatchesRegexp[S <: String](implicit
      ws: Witness.Aux[S]
  ): ValidatorForPredicate[String, MatchesRegex[S]] =
    ValidatorForPredicate.fromPrimitiveValidator(Validator.pattern(ws.value))

  implicit def validatorForLess[N: Numeric, NM <: N](implicit ws: Witness.Aux[NM]): ValidatorForPredicate[N, Less[NM]] =
    ValidatorForPredicate.fromPrimitiveValidator(Validator.max(ws.value, exclusive = true))

  implicit def validatorForLessEqual[N: Numeric, NM <: N](implicit
      ws: Witness.Aux[NM]
  ): ValidatorForPredicate[N, LessEqual[NM]] =
    ValidatorForPredicate.fromPrimitiveValidator(Validator.max(ws.value))

  implicit def validatorForGreater[N: Numeric, NM <: N](implicit ws: Witness.Aux[NM]): ValidatorForPredicate[N, Greater[NM]] =
    ValidatorForPredicate.fromPrimitiveValidator(Validator.min(ws.value, exclusive = true))

  implicit def validatorForGreaterEqual[N: Numeric, NM <: N](implicit
      ws: Witness.Aux[NM]
  ): ValidatorForPredicate[N, GreaterEqual[NM]] =
    ValidatorForPredicate.fromPrimitiveValidator(Validator.min(ws.value))
}

trait ValidatorForPredicate[V, P] {
  def validator: Validator[V]
  def validationErrors(value: V, refinedErrorMessage: String): List[ValidationError[_]]
}

object ValidatorForPredicate {
  def fromPrimitiveValidator[V, P](v: Validator.Primitive[V]): ValidatorForPredicate[V, P] =
    new ValidatorForPredicate[V, P] {
      override def validator: Validator[V] = v
      override def validationErrors(value: V, refinedErrorMessage: String): List[ValidationError[_]] =
        List(ValidationError.Primitive[V](v, value))
    }
}

trait LowPriorityValidatorForPredicate {
  implicit def genericValidatorForPredicate[V, P: ClassTag](implicit
      refinedValidator: Validate[V, P]
  ): ValidatorForPredicate[V, P] =
    new ValidatorForPredicate[V, P] {
      override val validator: Validator.Custom[V] = Validator.Custom(
        { v =>
          if (refinedValidator.isValid(v)) {
            List.empty
          } else {
            List(ValidationError.Custom(v, implicitly[ClassTag[P]].runtimeClass.toString))
          }
        }
      ) //for the moment there is no way to get a human description of a predicate/validator without having a concrete value to run it

      override def validationErrors(value: V, refinedErrorMessage: String): List[ValidationError[_]] =
        List(ValidationError.Custom[V](value, refinedErrorMessage))
    }
}
