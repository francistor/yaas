package yaas.coding

import yaas.dictionary.RadiusDictionary
import yaas.dictionary.DiameterDictionary

///////////////////////////////////////////////////////////////////////////////////
// Radius

/**
 * Builder of various Radius Formats
 */
object RadiusSerialFormat {
  
  def newCSVFormat(attrList: List[String]) = new CSVRadiusSerialFormat(attrList)
  
  // Defaults to empty attribute list => All attributes are printed
  def newLivingstoneFormat(attrList: List[String] = List()) = new LivingstoneRadiusSerialFormat(attrList)
  def newJSONFormat(attrList: List[String] = List()) = new JSONRadiusSerialFormat(attrList)
}

/**
 * Base class for Radius Formats.
 * 
 * Radius formats specify the attributes to be written in the CDR
 */
class RadiusSerialFormat(val attrList: List[String])


/**
 * Specifies the format of the CDR as Livingstone
 */
class LivingstoneRadiusSerialFormat(attrList: List[String]) extends RadiusSerialFormat(attrList)


/**
 * Specifies the format as CSV and holds the list of attributes to write
 */
class CSVRadiusSerialFormat(attrList: List[String]) extends RadiusSerialFormat(attrList)

/**
 * Specifies the format of the CDR as JSON
 */
class JSONRadiusSerialFormat(attrList: List[String]) extends RadiusSerialFormat(attrList)


///////////////////////////////////////////////////////////////////////////////////
// Diameter

object DiameterSerialFormat {
  
  def newJSONFormat() = new JSONDiameterSerialFormat()
  def newCSVFormat(attrList: List[String]) = new CSVDiameterSerialFormat(attrList)
}


/**
 * Base class for Diameter Formats.
 * 
 * Radius formats specify the attributes to be written in the CDR
 */
class DiameterSerialFormat(val attrList: List[String])


/**
 * Specifies the format of the CDR as JSON. Attributes are not filtered
 */
class JSONDiameterSerialFormat extends DiameterSerialFormat(List())

/**
 * Specifies the format as CSV and holds the list of attributes to write
 */
class CSVDiameterSerialFormat(attrList: List[String]) extends DiameterSerialFormat(attrList)
