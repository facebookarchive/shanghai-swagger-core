package com.wordnik.swagger.converter

import com.wordnik.swagger.core._
import com.wordnik.swagger.model._

import org.slf4j.LoggerFactory

import scala.collection.mutable.{ ListBuffer, LinkedHashMap, HashSet, HashMap }
import com.wordnik.swagger.core.util._

object ModelConverters {
  private val LOGGER = LoggerFactory.getLogger(ModelConverters.getClass)

  val converters = new ListBuffer[ModelConverter]() ++ List(
    new MapConverter,
    new JodaDateTimeConverter,
    new SwaggerSchemaConverter
  )

  def typeMap = SwaggerTypes.primitives ++ (for(c <- converters) yield c.typeMap).flatten.toMap

  def addConverter(c: ModelConverter, first: Boolean = false) = {
    if(first) {
      val reordered = List(c) ++ converters
      converters.clear
      converters ++= reordered
    }
    else converters += c
  }

  def removeConverter(c: ModelConverter) = {
    converters -= c
  }

  def read(cls: ClassWrapper, t: Map[String, String] = Map.empty): Option[Model] = {
    val types = {
      if(t.isEmpty)typeMap
      else t
    }
    var model: Option[Model] = None
    val itr = converters.iterator
    while(model == None && itr.hasNext) {
      itr.next.read(cls, types) match {
        case Some(m) => {
          val filteredProperties = m.properties.filter(prop => skippedClasses.contains(prop._2.qualifiedType) == false)
          model = Some(m.copy(properties = filteredProperties))
        }
        case _ => model = None
      }
    }
    model
  }

  def readAll(cls: ClassWrapper): List[Model] = {
    val output = new HashMap[String, Model]
    var model = read(cls, typeMap)
    val propertyNames = new HashSet[String]

    // add subTypes
    model.map(_.subTypes.map(typeRef => {
      try{
        LOGGER.debug("loading subtype " + typeRef)
        val cls = SwaggerContext.loadClass(typeRef)
        read(cls, typeMap) match {
          case Some(model) => output += cls.getName -> model
          case _ =>
        }
      }
      catch {
        case e: Exception => LOGGER.error("can't load class " + typeRef)
      }
    }))

    // add properties
    model.map(m => {
      output += cls.getName -> m
      val checkedNames = new HashSet[String]
      addRecursive(cls, m, checkedNames, output)
    })
    output.values.toList
  }

  def addRecursive(modelCls: ClassWrapper, model: Model, checkedNames: HashSet[String], output: HashMap[String, Model]): Unit = {
    if(!checkedNames.contains(model.name)) {
      val propertyNames = new HashSet[String]
      val typeParams = modelCls.getRawClass.getTypeParameters.map(t => modelCls.getTypeArgument(t.getName))
      for (typeParam <- typeParams) {
        propertyNames += typeParam.getName
      }
      for((name, property) <- model.properties) {
        val propertyName = property.items match {
          case Some(item) => item.qualifiedType.getOrElse(item.`type`)
          case None => property.qualifiedType
        }
        propertyNames += propertyName
      }
      for(typeRef <- propertyNames) {
        if(ignoredPackages.contains(getPackage(typeRef))) None
        else if(ignoredClasses.contains(typeRef)) {
          None
        }
        else if(!checkedNames.contains(typeRef)) {
          try{
            checkedNames += typeRef
            val cls = SwaggerContext.loadClass(typeRef)
            LOGGER.debug("reading class " + cls)
            ModelConverters.read(cls, typeMap) match {
              case Some(model) => {
                output += typeRef -> model
                addRecursive(cls, model, checkedNames, output)
              }
              case None =>
            }
          }
          catch {
            case e: ClassNotFoundException => 
          }
        }
      }
    }
  }

  def toName(cls: ClassWrapper): String = {
    var name: String = null
    val itr = converters.iterator
    while(name == null && itr.hasNext) {
      name = itr.next.toName(cls)
    }
    name
  }

  def getPackage(str: String): String = {
    str.lastIndexOf(".") match {
      case -1 => ""
      case e: Int => str.substring(0, e)
    }
  }

  def ignoredPackages: Set[String] = {
    (for(converter <- converters) yield converter.ignoredPackages).flatten.toSet
  }

  def ignoredClasses: Set[String] = {
    (for(converter <- converters) yield converter.ignoredClasses).flatten.toSet
  }

  def skippedClasses: Set[String] = {
    (for(converter <- converters) yield converter.skippedClasses).flatten.toSet
  }
}

trait ModelConverter {
  def read(cls: ClassWrapper, typeMap: Map[String, String]): Option[Model]
  def toName(cls: ClassWrapper): String
  def toDescriptionOpt(cls: ClassWrapper): Option[String]

  def ignoredPackages: Set[String] = Set("java.lang")
  def ignoredClasses: Set[String] = Set("java.util.Date")

  def typeMap = Map[String, String]()

  // these classes will be completely ignored
  def skippedClasses: Set[String] = Set()
}
