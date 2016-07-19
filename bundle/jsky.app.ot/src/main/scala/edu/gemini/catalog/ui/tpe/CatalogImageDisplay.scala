package edu.gemini.catalog.ui.tpe

import edu.gemini.catalog.image.ImageCatalog
import jsky.catalog.TableQueryResult
import jsky.catalog.gui.{TablePlotter, BasicTablePlotter}
import jsky.coords.WorldCoords
import jsky.image.fits.gui.FITSKeywordsFrame
import jsky.image.gui.ImageDisplayMenuBar
import jsky.image.gui.ImageDisplayToolBar
import jsky.image.gui.DivaMainImageDisplay
import jsky.navigator._
import jsky.util.gui.{ProxyServerDialog, DialogUtil}
import javax.swing._
import java.awt._
import java.awt.event.{ActionListener, ActionEvent}
import java.awt.geom.AffineTransform

import scalaz._
import Scalaz._

/**
  * Interface to encapsulate an object that can display a catalog and interact with the TPE
  */
trait CatalogDisplay {
  def plotter: TablePlotter

  /**
    * Return the base or center position in world coordinates.
    * If there is no base position, this method returns the center point
    * of the image. If the image does not support WCS, this method returns (0,0).
    * The position returned here should be used as the base position
    * for any catalog or image server requests.
    */
  def getBasePos: WorldCoords
}

/**
  * Extends the DivaMainImageDisplay class by adding support for
  * browsing catalogs and plotting catalog symbols on the image.
  */
abstract class CatalogImageDisplay(parent: Component, navigatorPane: NavigatorPane) extends DivaMainImageDisplay(navigatorPane, parent) with CatalogDisplay {
  val plotter = new BasicTablePlotter(getCanvasGraphics, getCoordinateConverter) <| {navigatorPane.setPlotter}

  /**
    * Load the sky image for the current location
   */
  def loadSkyImage(): Unit

  /** Display the FITS table at the given HDU index. */
  override def displayFITSTable(hdu: Int):Unit = {
    try {
      val fitsImage = getFitsImage
      val table = new NavigatorFITSTable(getFilename, fitsImage.getFits, hdu)
      val fitsKeywordsFrame = getFitsKeywordsFrame
      Option(fitsKeywordsFrame).foreach {
        case frame: FITSKeywordsFrame =>
          frame.getFITSKeywords.updateDisplay(hdu)
        case _ =>
      }
    } catch {
      case e: Exception =>
        DialogUtil.error(this, e)
    }
  }

  /**
    * Save (or update) the given table as a FITS table in the current FITS image.
    * NOTE This function was called from the removed menu "Save Image With Catalog Overlays"
    */
  def saveFITSTable(table: TableQueryResult):Unit = {
    Option(getFitsImage).ifNone {
      DialogUtil.error(this, "This operation is only supported on FITS files.")
    }
    Option(getFitsImage).foreach { i =>
      try {
        val newTable = NavigatorFITSTable.saveWithImage(getFilename, i.getFits, table)
        Option(newTable).foreach { t =>
          setSaveNeeded(true)
          checkExtensions(true)
          plotter.unplot(table)
          // TODO Should the table be displaye?
          //setQueryResult(newTable.getCatalog)
        }
      } catch {
        case e: Exception =>
          DialogUtil.error(this, e)
      }
    }
  }

  /**
    * This method is called before and after a new image is loaded, each time
    * with a different argument.
    *
    * @param before set to true before the image is loaded and false afterwards
    */
  protected override def newImage(before: Boolean) {
    super.newImage(before)
    if (!before) {
      // replot
      Option(plotter).foreach(_.replotAll())
    }
  }

  /**
    * Transform the image graphics using the given AffineTransform.
    */
  @Deprecated
  protected override def transformGraphics(trans: AffineTransform) {
    super.transformGraphics(trans)
    plotter.transformGraphics(trans)
  }

  /** Save any current catalog overlays as a FITS table in the image file. */
  def saveCatalogOverlaysWithImage(): Unit = {
    Option(plotter).foreach { p =>
      val tables = p.getTables
      Option(tables).foreach { t =>
        for (table <- t) saveFITSTable(table)
      }
    }
  }

}

/**
  * Extends the image display menubar by adding a catalog menu.
  */
class CatalogImageDisplayMenuBar(protected val imageDisplay: CatalogImageDisplay, toolBar: ImageDisplayToolBar) extends ImageDisplayMenuBar(imageDisplay, toolBar) {
  /** Handle for the Image menu */
  private val _catalogMenu = new JMenu("Catalog")
  /** Handle for the Help menu */
  private val _helpMenu = new JMenu("Help")

  val pickObjectMenuItem = getPickObjectMenuItem
  getViewMenu.remove(pickObjectMenuItem)
  _catalogMenu.add(imageServersMenu)
  _catalogMenu.add(proxySettingsMenuItem)
  _catalogMenu.add(pickObjectMenuItem)
  add(_catalogMenu)

  /** Return the handle for the Catalog menu */
  def getCatalogMenu: JMenu = _catalogMenu

  /** Return the handle for the Help menu */
  override def getHelpMenu: JMenu = _helpMenu

  /**
    * Create and return a submenu listing catalogs of the given type.
    *
    * @return the ne or updated menu
    */
  private def imageServersMenu: JMenu =
    ImageCatalog.all.foldLeft((new JMenu("Image Servers"), new ButtonGroup)) { case ((m, b), c) =>
      imageServersMenuItem(c) <| {m.add} <| {b.add} <| {_.setSelected(c == ImageCatalog.user)}
      (m, b)
    }._1

  /**
    * Create a menu item for accessing a specific catalog.
    */
  private def imageServersMenuItem(cat: ImageCatalog): JMenuItem = {
    val menuItem = new JRadioButtonMenuItem(cat.displayName) <| {_.addActionListener(new ActionListener() {
        override def actionPerformed(e: ActionEvent): Unit = {
          // First save the preference, then load the image
          ImageCatalog.user(cat)
          imageDisplay.loadSkyImage()
        }
      })}
    menuItem
  }

  /**
    * Create the Catalog => "Proxy Settings..." menu item
    */
  private def proxySettingsMenuItem: JMenuItem = {
    val proxyMenu = new JMenuItem("Proxy Settings...")
    proxyMenu.addActionListener(new ActionListener {
      override def actionPerformed(e: ActionEvent): Unit = {
        new ProxyServerDialog() <| {_.setVisible(true)}
      }
    })
    proxyMenu
  }

}
