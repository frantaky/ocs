package edu.gemini.model.p1.immutable

import scalaz.Lens

import edu.gemini.model.p1.{mutable => M, schema}
import scala.collection.JavaConverters._
import java.util.logging.{Logger, Level}
import org.xml.sax.SAXException

import scalaz._
import Scalaz._

object Proposal {

  // Lenses
  val meta:Lens[Proposal, Meta] = Lens.lensu((a, b) => a.copy(meta = b), _.meta)
  val semester:Lens[Proposal, Semester] = Lens.lensu((a, b) => a.copy(semester = b), _.semester)
  val title:Lens[Proposal, String] = Lens.lensu((a, b) => a.copy(title = b), _.title)
  val abstrakt:Lens[Proposal, String] = Lens.lensu((a, b) => a.copy(abstrakt = b), _.abstrakt)
  val scheduling:Lens[Proposal, String] = Lens.lensu((a, b) => a.copy(scheduling = b), _.scheduling)
  val tacCategory:Lens[Proposal, Option[TacCategory]] = Lens.lensu((a, b) => a.copy(tacCategory = b), _.tacCategory)
  val keywords:Lens[Proposal, List[Keyword]] = Lens.lensu((a, b) => a.copy(keywords = b), _.keywords)
  val investigators:Lens[Proposal, Investigators] = Lens.lensu((a, b) => a.copy(investigators = b), _.investigators)
  val observations:Lens[Proposal, List[Observation]] = Lens.lensu((a, b) => a.copy(observations = clean(b)), _.observations)
  val proposalClass:Lens[Proposal, ProposalClass] = Lens.lensu((a, b) => a.copy(proposalClass = b), _.proposalClass)

  val targets:Lens[Proposal, List[Target]] = Lens.lensu((a, b) => {
    val p = a.copy(targets = b.distinct)
    observations.set(p, p.observations) // force cleanup of empty obs
  }, _.targets)

  // Remove [partial] duplicates from appearing in the observation list
  def clean(os:List[Observation]) = {

    // Calculate what's a partial obs of what. O(N^2) sadly
    val partial:Map[(Observation, Observation), Boolean] = (for {
      o0 <- os
      o1 <- os
    } yield ((o0, o1), o0.isPartialObservationOf(o1))).toMap

    (os :\ List.empty[Observation]) {(o, os) =>
      if (os.exists(x => partial((o, x)))) {
        os
      } else if (os.exists(x => partial((x, o)))) {
        os.map {
          case o0 if partial((o0, o)) => o
          case o0                     => o0
        }
      } else {
        o :: os
      }
    } filterNot (_.isEmpty)

  }

  private val validate = Option(System.getProperty("edu.gemini.model.p1.validate")).isDefined
  private val logger = Logger.getLogger(getClass.getName)

  // Read schema version from a system property which in turn is set from
  // the Bundle Context on the activator. The bundle will not start if the property is missing
  // but I'll add this check anyway for unit testing and non-OSGi usage
  lazy val currentSchemaVersion = Option(System.getProperty("edu.gemini.model.p1.schemaVersion")) match {
    case Some(x:String) => x
    case x              => sys.error("Should set schemaVersion property")
  }

  lazy val empty = apply(
    Meta.empty,
    Semester.current,
    "",
    "",
    "",
    None,
    List.empty[Keyword],
    Investigators.empty,
    List.empty[Target],
    List.empty[Observation],
    ProposalClass.empty,
    currentSchemaVersion)

  def apply(m:M.Proposal) = new Proposal(m)

}

case class Proposal(meta:Meta,
                    semester:Semester,
                    title:String,
                    abstrakt:String,
                    scheduling:String,
                    tacCategory:Option[TacCategory],
                    keywords:List[Keyword],
                    investigators:Investigators,
                    targets:List[Target],
                    observations:List[Observation],
                    proposalClass:ProposalClass,
                    schemaVersion:String) {

  // Here is the evil bit that's explained in a little more detail over in Observation. The idea is that once an
  // Observation is owned by a Proposal, it has to defer to the Proposal's target list. We have to do this with a
  // mutable variable because there's no letrec in Scala; functions can be recursive but data can't. An alternative
  // would be to require a Proposal every time we wanted to get the target out of an Observation, which is a road I
  // went down but it turns out to be pretty bad. This may end up being worse, but mercifully the effect is entirely
  // localized; the public API is entirely immutable and this little tapdance is the only true nugget of evil.
  observations.foreach(_.proposal = Some(this)) // field is package-private

  def isSubmitted = proposalClass.key.isDefined

  def check = {
    observations.forall(_.proposal == Some(this))
  }

  def fix() {
    observations.foreach(_.proposal = Some(this)) // field is package-private
  }

  def resetObservationMeta:Proposal = copy(observations = observations.map(_.copy(meta = None)))

  // Calculate the program time as a ratio of total time.
  private def timeSum(extract: Observation => Option[TimeAmount]): TimeAmount =
    TimeAmount.sum(observations.map(extract).flatten)

  lazy val programTime: TimeAmount = timeSum(_.progTime)
  lazy val partnerTime: TimeAmount = timeSum(_.partTime)

  lazy val programTimeRatio: Double = {
    val totalTime = programTime |+| partnerTime
    totalTime.isEmpty ? 1.0 | (programTime.hours / totalTime.hours)
  }

  private def this(m:M.Proposal) = this(
    Meta(m.getMeta),
    Semester(m.getSemester),
    m.getTitle,
    m.getAbstract.unwrapLines,
    m.getScheduling,
    Option(m.getTacCategory),
    m.getKeywords.getKeyword.asScala.toList,
    Investigators(m),
    m.getTargets.getSiderealOrNonsiderealOrToo.asScala.map(Target(_)).toList,
    m.getObservations.getObservation.asScala.map(Observation(_)).toList,
    Option(m.getProposalClass).map(ProposalClass(_)).getOrElse(ProposalClass.empty),  // TODO: get rid of the empty case
    m.getSchemaVersion)

  def conditions = observations.flatMap(_.condition).distinct
  def blueprints = observations.flatMap(_.blueprint).distinct

  def mutable = {
    val n = new Namer

    val m = Factory.createProposal
    m.setMeta(meta.mutable)
    m.setSemester(semester.mutable)
    m.setTitle(title)
    m.setAbstract(abstrakt)
    m.setScheduling(scheduling)
    m.setTacCategory(tacCategory.orNull)
    m.setKeywords(Factory.createKeywords())
    m.getKeywords.getKeyword.addAll(keywords.asJavaCollection)
    m.setInvestigators(investigators.mutable(n))
    val ts = Factory.createTargets()
    ts.getSiderealOrNonsiderealOrToo.addAll(targets.map(_.mutable(n)).asJava)
    m.setTargets(ts)

    val bs = Factory.createBlueprints()
    bs.getFlamingos2OrGmosNOrGmosS.addAll(blueprints.map(_.toChoice(n)).asJava)
    m.setBlueprints(bs)

    val cs = Factory.createConditions()
    cs.getCondition.addAll(conditions.map(_.mutable(n)).asJava)
    m.setConditions(cs)

    val os = Factory.createObservations()
    os.getObservation.addAll(observations.map(_.mutable(n)).asJava)
    m.setObservations(os)

    m.setProposalClass(ProposalClass.mutable(this, n))
    m.setSchemaVersion(schemaVersion)
    m

  }

  import Proposal._

  if (validate) try {
    schema.Validator.validate(mutable)
  } catch {
    case ex:SAXException => logger.warning(ex.getMessage)
    case ex:Exception    => logger.log(Level.WARNING, "Trouble validating proposal object.", ex)
  }

}

