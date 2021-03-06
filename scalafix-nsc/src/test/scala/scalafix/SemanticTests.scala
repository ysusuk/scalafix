package scalafix

import scala.collection.immutable.Seq
import scala.tools.cmd.CommandLineParser
import scala.tools.nsc.CompilerCommand
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.{meta => m}
import scalafix.nsc.ScalafixNscPlugin
import scalafix.rewrite.ExplicitImplicit
import scalafix.rewrite.Rewrite
import scalafix.rewrite.RewriteCtx
import scalafix.util.DiffAssertions
import scalafix.util.FileOps
import scalafix.util.Patch
import scalafix.util.TreePatch.AddGlobalImport
import scalafix.util.TreePatch.RemoveGlobalImport
import scalafix.util.logger

import org.scalatest.FunSuite

class SemanticTests extends FunSuite with DiffAssertions { self =>
  val rewrite: Rewrite = ExplicitImplicit
  val parseAsCompilationUnit: Boolean = false
  private val testGlobal: Global = {
    def fail(msg: String) =
      sys.error(s"ReflectToMeta initialization failed: $msg")
    val classpath = System.getProperty("sbt.paths.scalafixNsc.test.classes")
    val pluginpath = System.getProperty("sbt.paths.plugin.jar")
    val scalacOptions = Seq(
      "-Yrangepos",
      "-Ywarn-unused-import"
    ).mkString(" ", " ", " ")
    val options = "-cp " + classpath + " -Xplugin:" + pluginpath + ":" +
        classpath + " -Xplugin-require:scalafix" + scalacOptions

    val args = CommandLineParser.tokenize(options)
    val emptySettings = new Settings(
      error => fail(s"couldn't apply settings because $error"))
    val reporter = new StoreReporter()
    val command = new CompilerCommand(args, emptySettings)
    val settings = command.settings
    val g = new Global(settings, reporter)
    val run = new g.Run
    g.phase = run.parserPhase
    g.globalPhase = run.parserPhase
    g
  }

  private val fixer = new ScalafixNscPlugin(testGlobal).component
  private val g: fixer.global.type = fixer.global

  private def unwrap(gtree: g.Tree): g.Tree = gtree match {
    case g.PackageDef(g.Ident(g.TermName(_)), stat :: Nil) => stat
    case body => body
  }

  private def getTypedCompilationUnit(code: String): g.CompilationUnit = {
    import g._
    val unit = new CompilationUnit(newSourceFile(code, "<ReflectToMeta>"))
    val run = g.currentRun
    val phases = List(run.parserPhase, run.namerPhase, run.typerPhase)
    val reporter = new StoreReporter()
    g.reporter = reporter
    phases.foreach(phase => {
      g.phase = phase
      g.globalPhase = phase
      phase.asInstanceOf[GlobalPhase].apply(unit)
      val errors = reporter.infos.filter(_.severity == reporter.ERROR)
      val warnings = reporter.infos.filter(_.severity == reporter.WARNING)
      warnings.foreach(warning => logger.warn(s"""${warning.msg}
                                                 |${warning.pos.lineContent}
                                                 |${warning.pos.lineCaret}""".stripMargin))
      errors.foreach(error =>
        fail(s"""scalac ${phase.name} error: ${error.msg} at ${error.pos}
                |$code""".stripMargin))
    })
    unit
  }

  def fix(code: String, config: ScalafixConfig): String = {
    val Fixed.Success(fixed) = fixer.fix(getTypedCompilationUnit(code), config)
    fixed
  }
  case class MismatchException(details: String) extends Exception
  private def checkMismatchesModuloDesugarings(obtained: m.Tree,
                                               expected: m.Tree): Unit = {
    import scala.meta._
    def loop(x: Any, y: Any): Boolean = {
      val ok = (x, y) match {
        case (x, y) if x == null || y == null =>
          x == null && y == null
        case (x: Some[_], y: Some[_]) =>
          loop(x.get, y.get)
        case (x: None.type, y: None.type) =>
          true
        case (xs: Seq[_], ys: Seq[_]) =>
          xs.length == ys.length && xs.zip(ys).forall {
            case (x, y) => loop(x, y)
          }
        case (x: Tree, y: Tree) =>
          def sameStructure =
            x.productPrefix == y.productPrefix &&
              loop(x.productIterator.toList, y.productIterator.toList)
          sameStructure
        case _ =>
          x == y
      }
      if (!ok) {
        val structure = (x, y) match {
          case (t1: Tree, t2: Tree) =>
            s"""
               |Diff:
               |${t1.structure}
               |${t2.structure}
               |""".stripMargin
          case _ => ""
        }
        throw MismatchException(s"$x != $y$structure")
      } else true
    }
    loop(obtained, expected)
  }
  private def typeChecks(code: String): Unit = {
    try {
      getTypedCompilationUnit(code)
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
        fail(
          s"""Fixed source code does not typecheck!
             |Message: ${e.getMessage}
             |Reveal: ${logger.reveal(code)}
             |Code: $code""".stripMargin,
          e
        )
    }
  }

  private def parse(code: String): m.Tree = {
    import scala.meta._
    code.parse[Source].get
  }

  def check(original: String, expectedStr: String, diffTest: DiffTest): Unit = {
    val fixed = fix(diffTest.wrapped(), diffTest.config)
    val obtained = parse(diffTest.unwrap(fixed))
    val expected = parse(expectedStr)
    try {
      checkMismatchesModuloDesugarings(obtained, expected)
      if (diffTest.isSyntax) {
        assertNoDiff(obtained.syntax, expected.syntax)
      }
      if (!diffTest.noWrap) {
        typeChecks(diffTest.wrapped(fixed))
      }
    } catch {
      case MismatchException(details) =>
        val header = s"scala -> meta converter error\n$details"
        val fullDetails =
          s"""${logger.header("Expected")}
             |${expected.syntax}
             |${logger.header("Obtained")}
             |${obtained.syntax}""".stripMargin
        fail(s"$header\n$fullDetails")
    }
  }

  DiffTest.testsToRun.foreach { dt =>
    if (dt.skip) {
      ignore(dt.fullName) {}
    } else {
      test(dt.fullName) {
        check(dt.original, dt.expected, dt)
      }
    }
  }
}
