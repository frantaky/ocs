package edu.gemini.spModel.io.impl.migration

import edu.gemini.pot.sp.SPComponentType
import edu.gemini.spModel.core.{ProgramId, StandardProgramId, Site}
import edu.gemini.spModel.io.impl.SpIOTags
import edu.gemini.spModel.pio.xml.PioXmlUtil
import edu.gemini.spModel.pio.{Container, ParamSet, Document, Version}

import java.io.StringWriter

/**
 * Base trait for all migrations.
 */
trait Migration {

  import PioSyntax._

  def version: Version

  def conversions: List[Document => Unit]

  /** Applies all conversion functions in order if the document is older than
    * the `version`.
    */
  def updateProgram(d: Document): Unit =
    d.containers.find(_.getKind == SpIOTags.PROGRAM).filter { c =>
      c.getVersion.compareTo(version) < 0
    }.foreach { _ =>
      conversions.foreach(_.apply(d))
    }

  val ParamSetBase               = "base"
  val ParamSetObservation        = "Observation"
  val ParamSetTarget             = "spTarget"
  val ParamSetTemplateParameters = "Template Parameters"

  // Extract all the target paramsets
  protected def allTargets(d: Document, includeTemplates: Boolean = true): List[ParamSet] = {
    val names = Set(ParamSetBase, ParamSetTarget)

    val templateTargets = for {
      cs  <- d.findContainers(SPComponentType.TEMPLATE_PARAMETERS) if includeTemplates
      tps <- cs.allParamSets if tps.getName == ParamSetTemplateParameters
      ps  <- tps.allParamSets if names(ps.getName)
    } yield ps

    val obsTargets = for {
      obs <- d.findContainers(SPComponentType.OBSERVATION_BASIC)
      env <- obs.findContainers(SPComponentType.TELESCOPE_TARGETENV)
      ps  <- env.allParamSets if names(ps.getName)
    } yield ps

    templateTargets ++ obsTargets
  }

  /** (obs paramset, target paramset) paramset pairs **/
  protected def obsAndBases(d: Document): List[(ParamSet, ParamSet)] =
    for {
      obs <- d.findContainers(SPComponentType.OBSERVATION_BASIC)
      env <- obs.findContainers(SPComponentType.TELESCOPE_TARGETENV)
      ps  <- env.allParamSets if ps.getName == ParamSetBase
    } yield (obs.getParamSet(ParamSetObservation), ps)

  /** all observation paramsets **/
  protected def obs(d: Document): List[ParamSet] =
    d.findContainers(SPComponentType.OBSERVATION_BASIC)
     .map(_.getParamSet(ParamSetObservation))

  /** Writes the document to an XML String for debugging. */
  protected def formatDocument(d: Document): String = {
    val writer = new StringWriter()
    PioXmlUtil.write(d, writer)
    writer.toString
  }

  /** Gets the site from the Program ID, if any. */
  protected def programSite(d: Document): Option[Site] =
    for {
      cont <- d.containers.find(_.getKind == SpIOTags.PROGRAM)
      pid  <- ProgramId.parseStandardId(cont.getName)
      site <- pid.site
    } yield site

}