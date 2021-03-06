package org.specs2
package control

import io.FilePath
import scala.sys.process.ProcessLogger

/**
 * Execute external commands
 */
object Executable {

  /**
   * Run an external program
   */
  def run(executable: FilePath, arguments: Seq[String] = Seq()): Operation[Unit] = {
    val logger = new StringProcessLogger
    try {

      val code = sys.process.Process(executable.path, arguments).!(logger)
      if (code == 0) Operations.ok(())
      else           Operations.fail(logger.lines)
    } catch { case t: Throwable =>
      Operations.fail(t.getMessage+"\n"+logger.lines)
    }
  }

  /**
   * Execute an external program and return the output
   */
  def execute(executable: FilePath, arguments: Seq[String] = Seq()): Operation[String] = {
    val logger = new StringProcessLogger
    try {

      val code = sys.process.Process(executable.path, arguments).!(logger)
      if (code == 0) Operations.ok(logger.lines)
      else           Operations.fail(logger.lines)
    } catch { case t: Throwable =>
      Operations.fail(t.getMessage+"\n"+logger.lines)
    }
  }

  val NullProcessLogger = new ProcessLogger {
    def buffer[T](f: => T): T = f
    def err(s: => String) {}
    def out(s: => String) {}
  }

  def stringProcessLogger = new StringProcessLogger
  class StringProcessLogger extends ProcessLogger {
    private val messages = new StringBuilder
    def lines = messages.toString

    def buffer[T](f: => T): T = {
      messages.clear
      f
    }
    def err(s: => String) { messages.append(s+"\n"); () }
    def out(s: => String) { messages.append(s+"\n"); () }
  }


}
