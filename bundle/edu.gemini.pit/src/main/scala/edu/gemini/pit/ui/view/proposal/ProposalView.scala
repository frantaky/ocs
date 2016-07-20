package edu.gemini.pit.ui.view.proposal

import com.jgoodies.forms.factories.Borders.DLU4_BORDER
import edu.gemini.model.p1.immutable._
import edu.gemini.pit.model.Model
import edu.gemini.pit.ui.{ShellAdvisor, URLConstants}
import edu.gemini.pit.ui.binding.BoundControls._
import edu.gemini.pit.ui.binding._
import edu.gemini.pit.ui.editor._
import edu.gemini.pit.ui.util.SimpleToolbar.StaticText
import edu.gemini.pit.ui.util._
import edu.gemini.pit.util._
import java.io.File
import javax.swing.{BorderFactory, Icon}

import scalaz._
import swing._
import event.ButtonClicked
import Scalaz._
import edu.gemini.pit.ui.util.gface.SimpleListViewer
import java.net.URI

import edu.gemini.shared.gui.Browser

class ProposalView(advisor:ShellAdvisor) extends BorderPanel with BoundView[Proposal] {panel =>
  implicit val boolMonoid = Monoid.instance[Boolean](_ || _,  false)

  // Bound
  override def children = List(title, abstrakt, /* scheduling, */ tacCategory, keywords, attachment, investigators)
  val lens = Model.proposal

  // Our content, which is defined below
  add(new GridBagPanel with Rows {
    border = DLU4_BORDER
    addRow(new Label("Title:"), title)
    addRow(new Label("Abstract:"), new ScrollPane(abstrakt), GridBagPanel.Fill.Both, 100)
    addRow(new Label("TAC Category:"), tacCategory)
    addRow(new Label("Keywords:"), keywords)
    addRow(new Label("Attachment:"), attachment)
    addSpacer()
  }, BorderPanel.Position.Center)
  add(investigators, BorderPanel.Position.South)

  // Refresh
  override def refresh(m:Option[Proposal]) {
    title.enabled = canEdit
    abstrakt.enabled = canEdit
    tacCategory.enabled = canEdit
    attachment.select.enabled = canEdit
    attachment.remove.enabled = canEdit
  }

  // Title field
  object title extends TextField with BoundText[Proposal] {
    val boundView = panel
    val lens = Proposal.title
  }

  // Abstract
  object abstrakt extends TextArea with BoundText[Proposal] {
    val boundView = panel
    val lens = Proposal.abstrakt
    rows = 5
    peer.setBorder(title.peer.getBorder)
    peer.setWrapStyleWord(true)
    peer.setLineWrap(true)
    border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
  }

  // TAC category
  object tacCategory extends ComboBox[TacCategory](TacCategory.values) with BoundCombo[Proposal, TacCategory] with Uninitialized.ValueRenderer[TacCategory] {
    val boundView = panel
    val lens = Uninitialized.lens(Proposal.tacCategory)
  }

  // Keywords
  object keywords extends BorderPanel with Bound[Proposal, List[Keyword]] {panel =>

    // Bound
    val lens = Proposal.keywords
    override val children = List(select, view)

    // Children, defined below
    add(select, BorderPanel.Position.West)
    add(view, BorderPanel.Position.Center)

    object select extends Button with Bound.Self[List[Keyword]] {
      icon = SharedIcons.ICON_SELECT
      override def refresh(ks:Option[List[Keyword]]) {
        enabled = ks.isDefined
      }
      reactions += {
        case ButtonClicked(_) => for {
          m <- model
          kws <- KeywordsEditor.selectKeywords(m, canEdit, panel)
        } model = Some(kws)
      }
    }

    object view extends Label with Bound.Self[List[Keyword]] {
      horizontalAlignment = Alignment.Left
      override def refresh(value:Option[List[Keyword]]) {
        val count = value.map(_.size).getOrElse(0)
        val all = value.map(_.map(_.value).mkString(": ", "; ", "")).getOrElse("")
        text = " %d Selected".format(count) + (if (count > 0) all else "")
        peer.setToolTipText(all)
      }
    }

  }

  // Attachment
  object attachment extends BorderPanel with Bound[Proposal, Option[File]] {panel =>

    // Bound
    val lens = Proposal.meta andThen Meta.attachment
    override def children = List(select, remove, label)

    // Our content, defined below
    add(selectPanel, BorderPanel.Position.West)
    add(label, BorderPanel.Position.Center)
    add(remove, BorderPanel.Position.East)

    // Panel for the buttons
    object selectPanel extends BorderPanel {panel =>
      add(templatesUrl, BorderPanel.Position.West)
      add(select, BorderPanel.Position.East)
    }

    // Select button
    object select extends Button with Bound.Self[Option[File]] {
      enabled = false
      icon = SharedIcons.ICON_ATTACH
      tooltip = "Select the PDF file of the text sections"
      override def refresh(m:Option[Option[File]]) {
        enabled = m.isDefined && canEdit
      }
      reactions += {
        case ButtonClicked(_) => for {
          file <- new Chooser[ProposalView]("attachment", panel.peer).chooseOpen("PDF Attachment", ".pdf")
        } model = Some(Some(file))
      }
    }

    // Remove button
    object remove extends Button with Bound.Self[Option[File]] {
      icon = SharedIcons.REMOVE
      tooltip = "Remove attachment."
      border = null
      override def refresh(m:Option[Option[File]]) {
        visible = ~m.map(_.isDefined)
        enabled = canEdit
      }
      reactions += {
        case ButtonClicked(_) => model = Some(None)
      }
    }

    // Label
    object label extends Label with Bound.Self[Option[File]] {
      horizontalAlignment = Alignment.Left
      override def refresh(f:Option[Option[java.io.File]]) {
        f.foreach {
          f =>
            text = " PDF attachment goes here."
            icon = null
            f.foreach {
              f =>
                val xml = advisor.shell.file
                val folder:Option[File] = Option(f.getParentFile).orElse(xml.map(_.getParentFile).flatMap(Option(_)))
                text = "%s (in folder %s)".format(f.getName, folder.map(_.getName).getOrElse("<none>"))
                icon = if (PDF.isPDF(xml, f)) SharedIcons.NOTE else new CompositeIcon(SharedIcons.NOTE, SharedIcons.OVL_ERROR)
            }
        }
      }
    }

  }

  // Get Templates button
  object templatesUrl extends Button {
    icon = SharedIcons.ICON_TEMPLATES
    tooltip = URLConstants.GET_TEMPLATES._2
    reactions += {
      case ButtonClicked(_) => Browser.open(new URI(URLConstants.GET_TEMPLATES._1))
    }
  }


  object investigators extends BorderPanel with Bound.Self[Proposal] {

    // Bound
    override def children = List(toolbar, listViewer)

    // Implicit to allow swapping and replacing of elements in a list.
    implicit def pimpList[A](as:List[A]):RichList[A] = new RichList(as)

    class RichList[A](as:List[A]) {
      def swap(i:Int, j:Int):Option[List[A]] = for {
        ai <- as.drop(i).headOption if i >= 0
        aj <- as.drop(j).headOption if j >= 0
      } yield as.zipWithIndex.map {
          case (_, n) if n == i => aj
          case (_, n) if n == j => ai
          case (a0, _)          => a0
        }

      def replace(a:A, b:A):List[A] = as.indexOf(a) match {
        case -1 => as
        case n  => as.take(n) ++ (b :: as.drop(n + 1))
      }
    }

    /**
     * Used to support demoting PIs and promoting CoIs.
     */
    object InvestigatorListView {
      var addrMap:Map[CoInvestigator, InstitutionAddress] = Map.empty

      // It doesn't play by the rules ...
      val dirtyLens:Lens[Investigators, List[Investigator]] = Lens.lensu((i, lst) => set(i, lst), i => get(i) )

      def get(invs:Investigators):List[Investigator] = invs.all

      def set(invs:Investigators, lst:List[Investigator]):Investigators = {
        // Remember the addresses of any PIs in the list in case they become
        // CoIs and lose the address information.
        addrMap = (addrMap /: lst) {
          (m, inv) =>
            inv match {
              case pi:PrincipalInvestigator => m + (pi.toCoi -> pi.address)
              case _                        => m
            }
        }

        // Promote the first investigator to PI status, putting back the address
        // information if we have it.
        val newPi = lst.head match {
          case pi:PrincipalInvestigator => pi
          case coi:CoInvestigator       =>
            def defaultInstitution(n: String): InstitutionAddress = {
              val inst = Institutions.bestMatch(coi.institution)
              def toIa(inst: Institution) = InstitutionAddress(inst.name, inst.addr.mkString("\n"), inst.country)
              inst.map(toIa(_)).getOrElse(InstitutionAddress(n))
            }

            coi.toPi.copy(address = addrMap.getOrElse(coi, defaultInstitution(coi.institution)))
        }

        // Create a new Investigators demoting every investigator after the head
        Investigators(newPi, lst.tail.map(_.toCoi))
      }
    }

    // Add our content, defined below
    add(listViewer, BorderPanel.Position.Center)
    add(toolbar, BorderPanel.Position.South)

    // The toolbar
    private object toolbar extends Panel with Bound[Proposal, Investigators] {

      // Bound
      val lens = Proposal.investigators
      override val children = List(del, add, up, down)

      // Our peer, and its content
      override lazy val peer = new SimpleToolbar
      children.map(_.peer).foreach(peer.add)

      // A trait for things that are bound to the coi list
      trait CoiBound extends Bound[Investigators, List[CoInvestigator]] {
        val lens = Investigators.cois
      }

      // Our add button
      object add extends ToolButton(SharedIcons.ADD, SharedIcons.ADD_DISABLED, "Add Co-Investigator") with CoiBound {
        override def refresh(m:Option[List[CoInvestigator]]) {
          enabled = m.isDefined && canEdit
        }
        def apply() {
          for {
            m <- model
            i <- CoiEditor.open(CoInvestigator.empty, canEdit, panel)
          } model = Some(m ++ List(i))
        }
      }

      // A trait for things bound to a phony complete list view of Investigators
      trait AllBound extends Bound[Investigators, List[Investigator]] {
        val lens = InvestigatorListView.dirtyLens
      }

      // A class of buttons that manipulate the investigator list without user input
      abstract class InvListButton(icon:Icon, disabledIcon:Icon, tooltip:String) extends ToolButton(icon, disabledIcon, tooltip) with AllBound {
        listViewer.onSelectionChanged(_ => enabled = potential.isDefined && canEdit)
        override def refresh(m:Option[List[Investigator]]) {
          enabled = potential.isDefined && canEdit
        }
        // Gets the new investigator list and the index of the investigator that
        // should get the selection.
        def potential:Option[(List[Investigator], Int)] // what's the effect of clicking, if any?
        def apply() {
          potential foreach {
            case (lst, sel) =>
              model = Some(lst)
              listViewer.selection = Some(model.get.apply(sel))
          }
        }
      }

      // A class of buttons that push investigators around in the list
      class MoveButton(delta:Int, icon:Icon, disabledIcon:Icon, tt:String) extends InvListButton(icon, disabledIcon, tt) {
        def potential = for {
          m <- model
          i <- listViewer.selection
          pos = m.indexOf(i)
          cs <- m.swap(pos, pos + delta)
        } yield (cs, pos + delta)
      }

      // Our delete button
      object del extends InvListButton(SharedIcons.REMOVE, SharedIcons.REMOVE_DISABLED, "Remove Co-Investigator") {
        def potential = for {
          m <- model
          i <- listViewer.selection
          pos = m.indexOf(i) if pos > 0
        } yield (m.take(pos) ++ m.drop(pos + 1), if (pos + 1 == m.size) pos - 1 else pos)
      }

      // Movement buttons
      object up extends MoveButton(-1, SharedIcons.ARROW_UP, SharedIcons.ARROW_UP_DISABLED, "Move Up")

      object down extends MoveButton(+1, SharedIcons.ARROW_DOWN, SharedIcons.ARROW_DOWN_DISABLED, "Move Down")

      // toolbar Text
      object text extends Label {
        override lazy val peer = new StaticText("Double-click an investigator to edit.")
      }

    }

    def editPi() { listViewer.editPi() }

    object listViewer extends SimpleListViewer[Proposal, Investigators, Investigator] {

      // Our action handlers
      onDoubleClick { edit(_) }

      def editPi() {
        model foreach { m => edit(m.pi) }
      }

      def edit(inv: Investigator) {
        inv match {
          case i:CoInvestigator => for {
            m <- model
            i0 <- CoiEditor.open(i, canEdit, panel)
          } model = Some(Investigators.cois.set(m, m.cois.replace(i, i0)))

          case i:PrincipalInvestigator => for {
            m <- model
            i0 <- PiEditor.open(i, canEdit, panel)
          } model = Some(Investigators.pi.set(m, i0))
        }
      }

      // One-liners
      val lens = Proposal.investigators
      def all(m:Investigators) = m.pi :: m.cois
      def size(m:Investigators) = all(m).size
      def elementAt(m:Investigators, i:Int) = all(m)(i)

      object columns extends Enumeration {
        val Name, Institution, Phone, Email = Value
      }

      import columns._

      def columnWidth = {
        case Name        => (125, Int.MaxValue)
        case Institution => (125, Int.MaxValue)
        case Phone       => (100, Int.MaxValue)
        case Email       => (100, Int.MaxValue)
      }

//      val piErr = new CompositeIcon(SharedIcons.ICON_USER, SharedIcons.OVL_ERROR)
//      val coiErr = new CompositeIcon(SharedIcons.ICON_USER_DIS, SharedIcons.OVL_ERROR)

      def icon(i:Investigator) = {
        case Name => i match {
          case _:PrincipalInvestigator => SharedIcons.ICON_USER // if (i.isComplete) SharedIcons.ICON_USER else piErr
          case _:CoInvestigator        => SharedIcons.ICON_USER_DIS // if (i.isComplete) SharedIcons.ICON_USER_DIS else coiErr
        }
      }

      def text(i:Investigator) = {
        case Name        => "%s %s".format(i.firstName, i.lastName)
        case Institution => i match {
          case pi:PrincipalInvestigator => pi.address.institution
          case coi:CoInvestigator       => coi.institution
        }
        case Phone       => i.phone.headOption.orNull
        case Email       => i.email
      }

    }

  }

  // public edit method for quick fix
  def edit(i:Investigator) = investigators.listViewer.edit(i)


}
