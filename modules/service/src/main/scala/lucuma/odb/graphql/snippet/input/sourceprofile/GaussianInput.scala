package lucuma.odb.graphql.snippet
package input
package sourceprofile

import lucuma.core.model.SourceProfile
import lucuma.odb.graphql.util.Bindings._
import cats.syntax.all._
import edu.gemini.grackle.Result

object GaussianInput {

  val CreateBinding: Matcher[SourceProfile.Gaussian] =
    ObjectFieldsBinding.rmap {
      case List(
        AngleInput.Binding.Option("fwhm", rFwhm),
        SpectralDefinitionInput.Integrated.CreateBinding.Option("spectralDefinition", rSpectralDefinition)
      ) =>
        (rFwhm, rSpectralDefinition).parTupled.flatMap {
          case (Some(fwhm), Some(spectralDefinition)) => Result(SourceProfile.Gaussian(fwhm, spectralDefinition))
          case _ => Result.failure("Both fwhm and spectralDefinition must be provided on creation")
        }
    }

}
