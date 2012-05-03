package com.wordnik.swagger.codegen

import com.wordnik.swagger.core._

import com.wordnik.swagger.codegen.util.CoreUtils
import com.wordnik.swagger.codegen.language.CodegenConfig

import org.fusesource.scalate._
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import org.fusesource.scalate.mustache._

import java.io.File
import java.io.FileWriter

import scala.io.Source
import scala.collection.mutable.{ HashMap, ListBuffer, HashSet }
import scala.collection.JavaConversions._

class Codegen(config: CodegenConfig) {
  val m = com.wordnik.swagger.core.util.JsonUtil.getJsonMapper

  def generateSource(bundle: Map[String, AnyRef], templateFile: String): String = {
    val allImports = new HashSet[String]
    val includedModels = new HashSet[String]
    val modelList = new ListBuffer[Map[String, AnyRef]]
    val models = bundle("models")
    models match {
      case e: List[Tuple2[String, DocumentationSchema]] => {
        e.foreach(m => {
          includedModels += m._1
          val modelMap = modelToMap(m._1, m._2)
          modelMap.getOrElse("imports", None) match {
            case im: Set[Map[String, String]] => im.foreach(m => m.map(e => allImports += e._2))
            case None =>
          }
          modelList += modelMap
        })
      }
      case None =>
    }

    val modelData = Map[String, AnyRef]("model" -> modelList.toList)
    val operationList = new ListBuffer[Map[String, AnyRef]]
    val classNameToOperationList = new HashMap[String, ListBuffer[AnyRef]]

    val apis = bundle("apis")
    apis match {
      case a: Map[String, List[(String, DocumentationOperation)]] => {
        a.map(op => {
          val classname = op._1
          val ops = op._2
          for ((apiPath, operation) <- ops) {
            val opList = classNameToOperationList.getOrElse(classname, {
              val lb = new ListBuffer[AnyRef]
              classNameToOperationList += classname -> lb
              lb
            })
            opList += apiToMap(apiPath, operation)
            CoreUtils.extractModelNames(operation).foreach(i => allImports += i)
          }
        })
      }
      case None =>
    }

    val f = new ListBuffer[AnyRef]
    classNameToOperationList.map(m => f += Map("classname" -> m._1, "operation" -> m._2))

    val imports = new ListBuffer[Map[String, String]]
    val importScope = config.modelPackage match {
      case Some(s) => s + "."
      case None => ""
    }

    // do the mapping before removing primitives!
    allImports.foreach(i => includedModels.contains(i) match {
      case false => {
        config.importMapping.containsKey(i) match {
          case true => imports += Map("import" -> config.importMapping(i))
          case false =>
        }
      }
      case true =>
    })
    allImports --= config.primitiveTypes
    allImports --= CoreUtils.primitives
    allImports --= CoreUtils.containers
    allImports.foreach(i => includedModels.contains(i) match {
      case false => {
        config.importMapping.containsKey(i) match {
          case true =>
          case false => imports += Map("import" -> (importScope + i))
        }
      }
      case true => // no need to add the model
    })

    val rootDir = new java.io.File(".")
    val engine = new TemplateEngine(Some(rootDir))

    val template = engine.compile(
      TemplateSource.fromText(config.templateDir + File.separator + templateFile,
        Source.fromFile(config.templateDir + File.separator + templateFile).mkString))

    var data = Map[String, AnyRef](
      "package" -> bundle("package"),
      "imports" -> imports,
      "operations" -> f,
      "models" -> modelData,
      "basePath" -> bundle.getOrElse("basePath", ""))

    var output = engine.layout(config.templateDir + File.separator + templateFile, template, data.toMap)
    output
  }

  def extractImportsFromApi(operation: Tuple2[String, DocumentationOperation]) = {
    val imports = new ListBuffer[AnyRef]
    val modelNames = CoreUtils.extractModelNames(operation._2)

    modelNames.foreach(modelName => {
      // apply mapings, packages for generated code
      val qualifiedModel = (config.importMapping.contains(modelName) match {
        case true => config.importMapping(modelName)
        case false => {
          config.modelPackage match {
            case Some(p) => p + "." + modelName
            case None => modelName
          }
        }
      })
      imports += Map("import" -> qualifiedModel)
    })
    imports.toSet
  }

  def apiToMap(path: String, op: DocumentationOperation): Map[String, AnyRef] = {
    var bodyParam: Option[String] = None

    var queryParams = new ListBuffer[AnyRef]
    val pathParams = new ListBuffer[AnyRef]
    val headerParams = new ListBuffer[AnyRef]
    var paramList = new ListBuffer[HashMap[String, AnyRef]]

    if (op.getParameters != null) {
      op.getParameters.foreach(param => {
        val params = new HashMap[String, AnyRef]
        params += "type" -> param.paramType
        params += "defaultValue" -> config.toDefaultValue(param.dataType, param.defaultValue)
        params += "dataType" -> config.toDeclaredType(param.dataType)
        params += "hasMore" -> "true"
        param.paramType match {
          case "body" => {
            params += "paramName" -> "body"
            params += "baseName" -> "body"
            param.required match {
              case true => params += "required" -> "true"
              case _ =>
            }
            bodyParam = Some("body")
          }
          case "path" => {
            params += "paramName" -> config.toVarName(param.name)
            params += "baseName" -> param.name
            params += "required" -> "true"
            pathParams += params.clone
          }
          case "query" => {
            params += "paramName" -> config.toVarName(param.name)
            params += "baseName" -> param.name
            params += "required" -> param.required.toString
            queryParams += params.clone
          }
          case "header" => {
            params += "paramName" -> config.toVarName(param.name)
            params += "baseName" -> param.name
            params += "required" -> param.required.toString
            headerParams += params.clone
          }
        }
        paramList += params
      })
    }

    val requiredParams = new ListBuffer[HashMap[String, AnyRef]]
    paramList.filter(p => p.contains("required") && p("required") == "true").foreach(param => {
      requiredParams += HashMap(
        "paramName" -> param("paramName"),
        "defaultValue" -> param("defaultValue"),
        "baseName" -> param("baseName"),
        "hasMore" -> "true")
    })
    requiredParams.size match {
      case 0 =>
      case _ => requiredParams.last.asInstanceOf[HashMap[String, String]] -= "hasMore"
    }

    queryParams.size match {
      case 0 =>
      case _ => queryParams.last.asInstanceOf[HashMap[String, String]] -= "hasMore"
    }

    val sp = {
      val lb = new ListBuffer[AnyRef]
      paramList.foreach(i => {
        i("defaultValue") match {
          case Some(e) =>
          case None => lb += i
        }
      })
      paramList.foreach(i => {
        i("defaultValue") match {
          case Some(e) => lb += i
          case None =>
        }
      })
      lb.toList
    }

    paramList.size match {
      case 0 =>
      case _ => sp.last.asInstanceOf[HashMap[String, String]] -= "hasMore"
    }

    val properties =
      HashMap[String, AnyRef](
        "path" -> path,
        "nickname" -> config.toMethodName(op.nickname),
        "summary" -> op.summary,
        "notes" -> op.notes,
        "deprecated" -> op.deprecated,
        "bodyParam" -> bodyParam,
        "allParams" -> sp,
        "pathParams" -> pathParams,
        "queryParams" -> queryParams,
        "headerParams" -> headerParams,
        "requiredParams" -> requiredParams,
        "httpMethod" -> op.httpMethod.toUpperCase,
        op.httpMethod.toLowerCase -> "true")
    if (requiredParams.size > 0) properties += "requiredParamCount" -> requiredParams.size.toString
    op.responseClass.indexOf("[") match {
      case -1 => {
        properties += "returnType" -> config.processResponseClass(op.responseClass)
        properties += "returnBaseType" -> config.processResponseClass(op.responseClass)
      }
      case n: Int => {
        val ComplexTypeMatcher = ".*\\[(.*)\\].*".r
        val ComplexTypeMatcher(basePart) = op.responseClass
        properties += "returnType" -> config.processResponseClass(op.responseClass)
        properties += "returnContainer" -> (op.responseClass.substring(0, n))
        properties += "returnBaseType" -> Some(basePart)
      }
    }
    properties.toMap
  }

  def modelToMap(className: String, model: DocumentationSchema): Map[String, AnyRef] = {
    val data: HashMap[String, AnyRef] =
      HashMap("classname" -> className,
        "modelPackage" -> config.modelPackage,
        "newline" -> "\n")

    val l = new ListBuffer[AnyRef]

    val imports = new HashSet[AnyRef]
    model.properties.map(prop => {
      val obj = prop._2
      val dt = obj.getType //.charAt(0).toUpperCase + obj.getType.substring(1)
      imports += Map("import" -> CoreUtils.extractBasePartFromType(dt))
      val properties =
        HashMap(
          "name" -> config.toVarName(prop._1),
          "baseName" -> prop._1,
          "datatype" -> config.toDeclaration(obj)._1,
          "defaultValue" -> config.toDeclaration(obj)._2,
          "description" -> obj.description,
          "notes" -> obj.notes,
          "required" -> obj.required.toString,
          "getter" -> config.toGetter(prop._1, config.toDeclaration(obj)._1),
          "setter" -> config.toSetter(prop._1, config.toDeclaration(obj)._1),
          "hasMore" -> "true")
      l += properties
    })
    l.size match {
      case 0 =>
      case _ => l.last.asInstanceOf[HashMap[String, String]] -= "hasMore"
    }
    data += "vars" -> l
    data += "imports" -> imports.toSet
    data.toMap
  }

  def writeSupportingClasses = {
    val rootDir = new java.io.File(".")
    val engine = new TemplateEngine(Some(rootDir))

    val data: HashMap[String, String] =
      HashMap(
        "package" -> config.packageName)

    config.supportingFiles.map(file => {
      val srcTemplate = file._1
      val outputDir = file._2
      val destFile = file._3

      val template = engine.compile(
        TemplateSource.fromText(config.templateDir + File.separator + srcTemplate,
          Source.fromFile(config.templateDir + File.separator + srcTemplate).mkString))

      val output = engine.layout(config.templateDir + File.separator + srcTemplate, template, data.toMap)

      val outputFolder = new File(outputDir.replaceAll("\\.", File.separator))
      outputFolder.mkdirs

      val filename = outputFolder + File.separator + destFile

      val fw = new FileWriter(filename, false)
      fw.write(output + "\n")
      fw.close()
      println("wrote " + filename)
    })

  }
}