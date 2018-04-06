package edu.gemini.spModel.gemini.ghost

import edu.gemini.shared.util.immutable.ImList
import edu.gemini.spModel.core.{Coordinates, SiderealTarget}
import edu.gemini.spModel.target.SPTarget
import edu.gemini.spModel.target.env.{TargetEnvironment, UserTarget}

import scalaz._
import Scalaz._

/** Allow conversions between target environments containing the different GHOST asterism types.
  * Coordinates are immutable, but SPTargets are mutable, so we must clone them.
  */
object GhostAsterismConverters {
  import GhostAsterism._
  import GhostAsterism.GhostStandardResTargets._

  type BasePosition = Option[Coordinates]
  type SkyPosition  = Option[Coordinates]

  sealed trait AsterismConverter {
    def name: String
    def convert(env: TargetEnvironment): String \/ TargetEnvironment
  }

  sealed trait GhostAsterismConverter extends AsterismConverter {
    override def convert(env: TargetEnvironment): String \/ TargetEnvironment = env.getAsterism match {
        case StandardResolution(SingleTarget(t), b)     => creator(env, t,  None,   b, None).right
        case StandardResolution(DualTarget(t1, t2), b)  => creator(env, t1, None,   b, t2UT(t2.spTarget).some).right
        case StandardResolution(TargetPlusSky(t, s), b) => creator(env, t,  s.some, b, None).right
        case StandardResolution(SkyPlusTarget(s, t), b) => creator(env, t,  s.some, b, None).right
        case HighResolution(t, s, b)                    => creator(env, t,  s,      b, None).right
        case _                                          => s"Could not convert to $name".left
      }

    protected def creator(env: TargetEnvironment, t1: GhostTarget, t2: Option[GhostTarget], s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment
  }

  case object GhostSingleTargetConverter extends GhostAsterismConverter {
    override def name: String = "GhostAsterism.SingleTarget"

    override protected def creator(env: TargetEnvironment, t: GhostTarget, s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment = {
      val asterism    = StandardResolution(SingleTarget(t), b)
      val userTargets = appendCoords(appendTarget(env.getUserTargets, u), s)
      TargetEnvironment.createWithClonedTargets(asterism, env.getGuideEnvironment, userTargets)
    }
  }

  case object GhostDualTargetConverter extends GhostAsterismConverter {
    override def name: String = "GhostAsterism.DualTarget"

    override protected def creator(env: TargetEnvironment, t: GhostTarget, s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment = {
      val asterism    = StandardResolution(DualTarget(t, GhostTarget.empty), b)
      val userTargets = appendCoords(appendTarget(env.getUserTargets, u), s)
      TargetEnvironment.createWithClonedTargets(asterism, env.getGuideEnvironment, userTargets)
    }
  }

  case object GhostTargetPlusSkyConverter extends GhostAsterismConverter {
    override def name: String = "GhostAsterism.TargetPlusSky"

    override protected def creator(env: TargetEnvironment, t: GhostTarget, s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment = {
      val asterism    = StandardResolution(TargetPlusSky(t, s.getOrElse(Coordinates.zero)), b)
      val userTargets = appendTarget(env.getUserTargets, u)
      TargetEnvironment.createWithClonedTargets(asterism, env.getGuideEnvironment, userTargets)
    }
  }

  case object GhostSkyPlusTargetConverter extends GhostAsterismConverter {
    override def name: String = "GhostAsterism.SkyPlusTarget"

    override protected def creator(env: TargetEnvironment, t: GhostTarget, s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment = {
      val asterism    = StandardResolution(SkyPlusTarget(s.getOrElse(Coordinates.zero), t), b)
      val userTargets = appendTarget(env.getUserTargets, u)
      TargetEnvironment.createWithClonedTargets(asterism, env.getGuideEnvironment, userTargets)
    }
  }

  case object GhostHighResolutionConverter extends GhostAsterismConverter {
    override def name: String = "GhostAsterism.HighResolution"

    override protected def creator(env: TargetEnvironment, t: GhostTarget, s: SkyPosition, b: BasePosition, u: Option[UserTarget]): TargetEnvironment = {
      val asterism    = HighResolution(t, s, b)
      val userTargets = appendTarget(env.getUserTargets, u)
      TargetEnvironment.createWithClonedTargets(asterism, env.getGuideEnvironment, userTargets)
    }
  }


  private def appendCoords(userTargets: ImList[UserTarget], c: Option[Coordinates]): ImList[UserTarget] =
    appendTarget(userTargets, c.map(c2UT))

  private def appendTarget(userTargets: ImList[UserTarget], t: Option[UserTarget]): ImList[UserTarget] =
    t.map(userTargets.append).getOrElse(userTargets)

  /** Convert an SPTarget to an empty UserTarget. */
  private def t2UT(t: SPTarget): UserTarget =
    new UserTarget(UserTarget.Type.other, t)

  /** Convert a Coordinate to an empty UserTarget. */
  private def c2UT(c: Coordinates): UserTarget = {
    val t = SiderealTarget.coordinates.set(SiderealTarget.empty, c)
    t2UT(new SPTarget(t))
  }
}
