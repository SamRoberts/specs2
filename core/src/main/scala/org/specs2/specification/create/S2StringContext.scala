package org.specs2
package specification
package create

import execute._
import org.specs2.control.TraceLocation
import org.specs2.specification.process.{StatisticsRepository, Executor}
import text.Interpolated
import reflect.Compat210.blackbox
import reflect.Macros._
import text.NotNullStrings._
import text.Trim._
import org.specs2.main.{CommandLine, Arguments}
import specification.core._
import execute.DecoratedResult
import specification.core.RawText
import specification.core.Code

import scala.concurrent.ExecutionContext

/**
 * Allow to use fragments inside interpolated strings starting with s2 in order to build the specification content
 */
trait S2StringContext extends FragmentsFactory { outer =>
  private val ff = fragmentFactory

  implicit def stringIsInterpolatedFragment(s: =>String): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =  {
      val s1 =
        try s
        catch { case e: Throwable => s"[${e.getMessage.notNull}]" }
      fs append ff.text(text + s1).setLocation(start)
    }
  }

  implicit def fragmentIsInterpolatedFragment(f: Fragment): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =  {
      f match {
        // in the case of a tag which applies to the example just before,
        // if the tag is just separated by some empty text, append the tag close to the example
        case tag @ Fragment(Marker(_, _, false), _, _) if text.trim.isEmpty =>
          fs append tag.setLocation(end) append ff.text(text).setLocation(start)

        case other =>
          fs append ff.text(text).setLocation(start) append other.setLocation(end)
      }
    }
  }

  implicit def specificationLinkIsInterpolatedFragment(link: SpecificationLink): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) = {
      fs append ff.text(text).setLocation(start) append fragmentFactory.link(link).setLocation(end)
    }
  }

  implicit def lazyFragmentIsInterpolatedFragment(f: LazyFragment): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) = {
      fs append ff.text(text).setLocation(start) appendLazy f.fragment().setLocation(end)
    }
  }

  implicit def asResultIsInterpolatedFragment[R : AsResult](r: =>R): InterpolatedFragment =
    envFunctionIsInterpolatedFragment((env: Env) => r)

  implicit def envFunctionIsInterpolatedFragment[R : AsResult](f: Env => R): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =  {
      val texts = text.split("\n")
      val spaces = texts.lastOption.fold("")(_.takeWhile(Seq(' ', '\n').contains))
      val indent = spaces.mkString

      val first = if (texts.size > 1) texts.dropRight(1).mkString("", "\n", "\n") else ""
      val autoExample = texts.lastOption.exists(_.trim.isEmpty)

      val description =
        if (autoExample) Code(expression)
        else             RawText(texts.lastOption.fold("")(_.trim))

      val before =
        if (first.nonEmpty) Vector(ff.text(first + indent).setLocation(start))
        else                Vector()

      val result =
        implicitly[AsResult[R]] match {
          case v : AnyValueAsResult[_] => Env.executeResult(f) match {
            case DecoratedResult(t, e: Error) => before :+ ff.example(description, e).setLocation(end)
            case DecoratedResult(t, _)        => Vector(ff.text(text), ff.text(t.notNull)).map(_.setLocation(end))
          }
          case other                          => before :+ ff.example(description, Execution.withEnv(f)).setLocation(end)
        }
      fs append result
    }
  }

  implicit def argumentsFunctionIsInterpolatedFragment[R : AsResult](f: Arguments => R): InterpolatedFragment =
    envFunctionIsInterpolatedFragment((env: Env) => f(env.arguments))

  implicit def statsRepositoryFunctionIsInterpolatedFragment[R : AsResult](f: StatisticsRepository => R): InterpolatedFragment =
    envFunctionIsInterpolatedFragment((env: Env) => f(env.statisticsRepository))

  implicit def commandLineFunctionIsInterpolatedFragment[R : AsResult](f: CommandLine => R): InterpolatedFragment =
    envFunctionIsInterpolatedFragment((env: Env) => f(env.arguments.commandLine))

  implicit def executionContextFunctionIsInterpolatedFragment[R : AsResult](f: ExecutionContext => R): InterpolatedFragment =
    envFunctionIsInterpolatedFragment((env: Env) => f(ExecutionContext.fromExecutorService(env.executionEnv.executor,
      (t: Throwable) => control.logThrowable(t, env. arguments.verbose).execute(env.systemLogger).unsafePerformIO)))

  implicit def anyAsResultIsInterpolatedFragment(r: =>Function0Result): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =
      asResultIsInterpolatedFragment(AsResult(r)).append(fs, text, start, end, expression)
  }

  implicit def fragmentsIsInterpolatedFragment(fragments: Fragments): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =
      (fs append ff.text(text).setLocation(start)) append fragments
  }

  implicit def specificationStructureIsInterpolatedFragment(s: SpecificationStructure): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =
      specStructureIsInterpolatedFragment(s.is).append(fs, text, start, end, expression)
  }

  implicit def specStructureIsInterpolatedFragment(s: SpecStructure): InterpolatedFragment = new InterpolatedFragment {
    def append(fs: Fragments, text: String, start: Location, end: Location, expression: String) =
      (fs append ff.text(text).setLocation(start)) append s.fragments
  }

  /**
   * based on the interpolated variables and the expressions captured with the macro, create the appropriate fragments
   *
   * if the Yrangepos scalac option is not set then we use an approximated method to find the expressions texts
   */
  def s2(content: String, Yrangepos: Boolean, texts: Seq[String], 
         textsStartPositions: Seq[String], textsEndPositions: Seq[String],
         variables: Seq[InterpolatedFragment], rangeExpressions: Seq[String]): Fragments =  {

    val expressions = if (Yrangepos) rangeExpressions else new Interpolated(content, texts).expressions

    val (textsStartLocations1, textsEndLocations1) = 
      (positionsToLocation(textsStartPositions), positionsToLocation(textsEndPositions))

    val fragments = (texts zip variables zip expressions zip textsStartLocations1 zip textsEndLocations1).foldLeft(Fragments()) { (res, cur) =>
      val ((((text, variable), expression), startLocation), endLocation) = cur

      // always provide the latest full piece of text to the spec part for the append method
//      val (res1, text1) = res.lastOption.collect { case f @ Fragment(RawText(t), _, _) if !f.isRunnable =>
//        (res.dropRight(1), t + text)
//      }.getOrElse((res, text))
      variable.append(res, text, SimpleLocation(startLocation), SimpleLocation(endLocation), expression)
    }

    // The last piece of text is trimmed to allow the placement of closing quotes in the s2 string
    // to be on column 0 or aligned with examples and still have the same display when using the Text printer
    val last = texts.lastOption.map(_.trimEnd).filterNot(_.isEmpty).map(ff.text).toSeq

    fragments append Fragments(last:_*)
  }

  implicit class specificationInStringContext(sc: StringContext) {
    def s2(variables: InterpolatedFragment*) = macro S2Macro.s2Implementation
  }

  
  private def positionsToLocation(positions: Seq[String]): Seq[TraceLocation] =
    positions.map(_.split("\\|").toList).map {
      case path :: fileName :: line :: _ => TraceLocation(path, fileName, "Specification", "s2", line.toInt)
      // this case should not happen!
      case other                         => TraceLocation("not found", "file name", "Specification", "s2", 0)
    }
}

object S2StringContext extends DefaultFragmentFactory

object S2Macro {
  def s2Implementation(c: blackbox.Context)(variables: c.Expr[InterpolatedFragment]*) : c.Expr[Fragments] = {
    import c.{universe => u}; import u.{ Position => _, _ }

    val texts = c.prefix.tree match { case Apply(_, List(Apply(_, ts))) => ts }

    val macroPos = c.macroApplication.pos
    val fileContent = macroPos.source.content.mkString

    def contentFrom(pos: c.Position) = fileContent.split("\n").drop(pos.line - 1).mkString("\n").drop(pos.column-1)
    val content = contentFrom(macroPos).drop("s2\"\"\"".size)
    val Yrangepos = macroPos.isRange

    def traceLocation(pos: c.universe.Position) =
      Seq(pos.source.path, pos.source.file.name, pos.line).mkString("|")

    val textStartPositions = texts.map(t => c.literal(traceLocation(t.pos)).tree)
    val textEndPositions = texts.map(t => c.literal(traceLocation(t.pos.focusEnd)).tree)

    val result =
      c.Expr(methodCall(c)("s2",
        c.literal(content).tree,
        c.literal(Yrangepos).tree,
        toAST[List[_]](c)(texts:_*),
        toAST[List[_]](c)(textStartPositions:_*),
        toAST[List[_]](c)(textEndPositions:_*),
        toAST[List[_]](c)(variables.map(_.tree):_*),
        toAST[List[_]](c)(variables.map(stringExpr(c)(_)):_*)))

    c.Expr(atPos(c.prefix.tree.pos)(result.tree))

  }

}

trait InterpolatedFragment {
  def append(fragments: Fragments, text: String, start: Location, end: Location, expression: String): Fragments
}

