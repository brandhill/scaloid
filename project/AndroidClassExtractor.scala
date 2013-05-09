import sbt._
import Keys._

import java.beans.{Introspector, MethodDescriptor, PropertyDescriptor, ParameterDescriptor, IndexedPropertyDescriptor}
import java.lang.reflect._
import org.reflections._
import org.reflections.ReflectionUtils._
import org.reflections.scanners._
import scala.collection.JavaConversions._


object AndroidClassExtractor {

  def toScalaType(tpe: Type): ScalaType =
    tpe match {
      case null => throw new Error("Property cannot be null")
      case t: GenericArrayType =>
        ScalaType("Array", Seq(toScalaType(t.getGenericComponentType)))
      case t: ParameterizedType =>
        ScalaType(
          toScalaType(t.getRawType).name,
          t.getActualTypeArguments.map(toScalaType).toSeq
        )
      case t: TypeVariable[_] =>
        ScalaType(t.getName, t.getBounds.map(toScalaType).toSeq, true)
      case t: WildcardType => ScalaType("_")
      case t: Class[_] => {
        if (t.isArray) {
          ScalaType("Array", Seq(toScalaType(t.getComponentType)))
        } else if (t.isPrimitive) {
          ScalaType(t.getName match {
            case "void" => "Unit"
            case n => n.capitalize
          })
        } else {
          ScalaType(
            t.getName.replace("$", "."),
            t.getTypeParameters.take(1).map(_ => ScalaType("_")).toSeq
          )
        }
      }
      case _ =>
        throw new Error("Cannot find type of " + tpe.getClass + " ::" + tpe.toString)
    }

  private def isAbstract(m: Member): Boolean = Modifier.isAbstract(m.getModifiers)
  private def isAbstract(c: Class[_]): Boolean = Modifier.isAbstract(c.getModifiers)
  private def isFinal(m: Member): Boolean = Modifier.isFinal(m.getModifiers)
  private def isFinal(c: Class[_]): Boolean = Modifier.isFinal(c.getModifiers)

  private def methodSignature(m: Method): String = List(
    m.getName,
    m.getReturnType.getName,
    "["+m.getParameterTypes.map(_.getName).toList.mkString(",")+"]"
  ).mkString(":")

  private def methodSignature(mdesc: MethodDescriptor): String =
    methodSignature(mdesc.getMethod)

  private def propSignature(pdesc: PropertyDescriptor): String = List(
    pdesc.getName,
    pdesc.getPropertyType
  ).mkString(":")

  private def toAndroidClass(cls: Class[_]) = {
    implicit val _cls = cls

    val parentBeanInfo = Option(cls.getSuperclass).map(Introspector.getBeanInfo)


    val superProps: Set[String] = 
      parentBeanInfo.map { 
        _.getPropertyDescriptors.toList.map(propSignature).toSet
      }.getOrElse(Set())

    val superMethods: Set[String] = 
      parentBeanInfo.map {
        _.getMethodDescriptors.toList.map(methodSignature).toSet
      }.getOrElse(Set())

    val superGetters: Set[String] = 
      parentBeanInfo.map {
        _.getPropertyDescriptors.toList
          .map(m => Option(m.getReadMethod))
          .filter(_.nonEmpty)
          .map(_.get.getName).toSet
      }.getOrElse(Set())


    def toAndroidMethod(m: Method): AndroidMethod = {
      val name = m.getName
      val retType = AndroidClassExtractor.toScalaType(m.getGenericReturnType)
      val argTypes = Option(m.getGenericParameterTypes)
                      .flatten
                      .toSeq
                      .map(AndroidClassExtractor.toScalaType(_))
      val paramedTypes = (retType +: argTypes).filter(_.isVar).distinct

      AndroidMethod(name, retType, argTypes, paramedTypes, isAbstract(m))
    }

    def isValidProperty(pdesc: PropertyDescriptor): Boolean =
      (! pdesc.isInstanceOf[IndexedPropertyDescriptor]) && pdesc.getDisplayName.matches("^[a-zA-z].*") &&
      (! superProps(propSignature(pdesc)))

    def isListenerSetterOrAdder(mdesc: MethodDescriptor): Boolean = {
      val name = mdesc.getName
      name.matches("^(set|add).+Listener$") && !superMethods(methodSignature(mdesc))
    }

    def isCallbackMethod(mdesc: MethodDescriptor): Boolean =
      ! mdesc.getName.startsWith("get")

    def extractMethodsFromListener(callbackCls: Class[_]): List[AndroidMethod] =
      Introspector.getBeanInfo(callbackCls).getMethodDescriptors
        .filter(isCallbackMethod)
        .map(_.getMethod)
        .map(toAndroidMethod)
        .toList

    def getPolymorphicSetters(method: Method): Seq[AndroidMethod] = {
      val name = method.getName
      Introspector.getBeanInfo(cls).getMethodDescriptors.view
        .map(_.getMethod)
        .filter { m => 
          !isAbstract(m) && m.getName == name && 
            m.getParameterTypes.length == 1 && 
            !superMethods(methodSignature(m)) 
        }
        .map(toAndroidMethod)
        .toSeq
    }

    def toAndroidProperty(pdesc: PropertyDescriptor): Option[AndroidProperty] = {
      val name = pdesc.getDisplayName
      var nameClashes = false

      try {
        cls.getMethod(name)
        nameClashes = true
      } catch {
        case e: NoSuchMethodException => // does nothing
      }

      val readMethod = Option(pdesc.getReadMethod) 
      val writeMethod = Option(pdesc.getWriteMethod)

      val getter = readMethod
                      .filter(m => ! superMethods(methodSignature(m)))
                      .map { m =>
                        val am = toAndroidMethod(m)
                        if (superGetters(am.name))
                          am.copy(isOverride = true)
                        else
                          am
                      }

      val setters = writeMethod
                      .map(getPolymorphicSetters)
                      .toSeq.flatten
                      .sortBy(_.argTypes(0).name)

      (getter, setters) match {
        case (None, Nil) => None
        case (g, ss) =>
          val tpe = getter.map(_.retType).getOrElse(setters.first.argTypes.first)
          val switch = if (name.endsWith("Enabled")) Some(name.replace("Enabled", "").capitalize) else None
          Some(AndroidProperty(name, tpe, getter, setters, switch, nameClashes))
      }
    }



    
    def toAndroidListeners(mdesc: MethodDescriptor): Seq[AndroidListener] = {
      val method = mdesc.getMethod
      val setter = mdesc.getName
      val paramsDescs: List[ParameterDescriptor] = Option(mdesc.getParameterDescriptors).toList.flatten
      val callbackClassName = toScalaType(method.getGenericParameterTypes()(0)).name
      val callbackMethods   = extractMethodsFromListener(method.getParameterTypes()(0))

      callbackMethods.map { cm =>
        AndroidListener(
          cm.name,
          callbackMethods.find(_.name == cm.name).get.retType,
          cm.argTypes,
          cm.argTypes.nonEmpty,
          setter,
          callbackClassName,
          callbackMethods.map { icm =>
            AndroidCallbackMethod(
              icm.name,
              icm.retType,
              icm.argTypes,
              icm.name == cm.name
            )
          }
        )
      }.filter(_.isSafe)
    }

    def getHierarchy(c: Class[_], accu: List[String] = Nil): List[String] =
      if (c == null) accu
      else getHierarchy(c.getSuperclass, c.getName.split('.').last :: accu)

    val props = Introspector.getBeanInfo(cls).getPropertyDescriptors.toSeq
                  .filter(isValidProperty)
                  .map(toAndroidProperty)
                  .flatten
                  .sortBy(_.name)

    val listeners = Introspector.getBeanInfo(cls).getMethodDescriptors.toSeq
                  .filter(isListenerSetterOrAdder)
                  .map(toAndroidListeners)
                  .flatten
                  .sortBy(_.name)

    val fullName = cls.getName

    val name = fullName.split('.').last
    val tpe = toScalaType(cls)
    val pkg = fullName.split('.').init.mkString
    val parentType = Option(cls.getGenericSuperclass)
                        .map(toScalaType)
                        .filter(_.name.startsWith("android"))

    val constructors: Seq[Seq[ScalaType]] = getConstructors(cls).map(_.getGenericParameterTypes.map(toScalaType).toSeq).toSeq

    val isA = getHierarchy(cls).toSet

    AndroidClass(name, pkg, tpe, parentType, constructors, props, listeners, isA, isAbstract(cls), isFinal(cls))
  }

  def extractTask = (streams) map { s =>

    s.log.info("Extracting class info from Android...")

    val r = new Reflections("android", new SubTypesScanner(false), new TypeElementsScanner(), new TypeAnnotationsScanner())
    val clss: Set[Class[_]] = asScalaSet(r.getSubTypesOf(classOf[java.lang.Object])).toList.toSet
    val res = clss.toList
                .map(toAndroidClass)
                .filter {
                  s.log.info("Excluding inner classes for now - let's deal with it later")
                  ! _.name.contains("$")
                }
                .map(c => c.tpe.name -> c)
                .toMap

    val values = res.values.toList
    s.log.info("Done.")
    s.log.info("Classes: "+ values.length)
    s.log.info("Properties: "+ values.map(_.properties).flatten.length)
    s.log.info("Listeners: "+ values.map(_.listeners).flatten.length)
    res
  }
}

