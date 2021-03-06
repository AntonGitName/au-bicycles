package ru.spbau.mit.aush.execute

import org.apache.commons.io.IOUtils
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import ru.spbau.mit.aush.execute.cmd.CdExecutor
import ru.spbau.mit.aush.execute.cmd.CmdExecutor
import ru.spbau.mit.aush.execute.cmd.ExitExecutor
import ru.spbau.mit.aush.execute.error.CmdExecutionError
import ru.spbau.mit.aush.execute.process.ProcessBuilderCreator
import ru.spbau.mit.aush.execute.process.ProcessPiper
import ru.spbau.mit.aush.log.Logging
import ru.spbau.mit.aush.parse.Statement
import ru.spbau.mit.aush.parse.visitor.PrepareVisitor
import ru.spbau.mit.aush.parse.visitor.VarReplacingVisitor
import java.io.IOException

/**
 * Main interpreter class
 * Interpreter has it's own context with environmental variables and exit code.
 * Every command except assign command are executed in separate processes
 *
 * It uses System.in and System.out as in/out command streams, so not to
 * close whole interpreter builtin commands use EOF string as signal to stop
 *
 */
class AushInterpreter() {
    val logger = Logging.getLogger("AushInterpreter")
    val executorsClassNames: Map<String, String>
    val exitCmdName: String
    val cdCmdName: String

    init {
        val cmdPackage = "ru.spbau.mit.aush"
        // auto wiring AUSH built in commands executors
        val r = Reflections(ConfigurationBuilder()
                .setScanners(SubTypesScanner())
                .setUrls(ClasspathHelper.forPackage(cmdPackage)))
        executorsClassNames = r.getSubTypesOf(CmdExecutor::class.java)
                .map { Pair(it.newInstance(), it) }
                .map { it.first.name() to it.second.canonicalName }
                .toMap()
        exitCmdName = ExitExecutor().name()
        cdCmdName = CdExecutor().name()
    }

    /**
     * Executes given command as parsed statement
     */
    fun execute(statement: Statement) {
        // replacing variables
        val replacer = VarReplacingVisitor()
        val statementWithVars = replacer.replace(statement)

        val preparer = PrepareVisitor()
        val statementToExecute = preparer.prepareStatement(statementWithVars)

        when (statementToExecute) {
            is Statement.Cmd -> execSimpleCmd(statementToExecute)
            is Statement.Pipe -> execPipedCmd(statementToExecute)
            is Statement.Assign -> execAssignCmd(statementToExecute)
        }
    }

    private fun execSimpleCmd(statement: Statement.Cmd) {
        logger.info("executing ${statement.cmdName}")

        if (statement.cmdName == exitCmdName) {
            System.exit(AushContext.instance.getExitCode())
        }

        if (statement.cmdName == cdCmdName) {
            CdExecutor().exec(statement.args, System.`in`, System.out)
            return
        }

        val pb = getProcessBuilder(statement)
        pb.inheritIO()
        try {
            val process = pb.start()
            logger.info("Waiting for process to finish...")
            AushContext.instance.setExitCode(process.waitFor())
        } catch (e: IOException) {
            throw CmdExecutionError("Error running process: ${statement.cmdName}")
        }
    }

    private fun getProcessBuilder(statement: Statement.Cmd): ProcessBuilder {
        val pb = if (statement.cmdName in executorsClassNames) {
            ProcessBuilderCreator.createBuiltinCmdPB(
                    executorsClassNames[statement.cmdName]!!,
                    statement.args
            )
        } else {
            ProcessBuilderCreator.createExternalCmdPB(
                    statement.cmdName,
                    statement.args
            )
        }
        pb.environment().putAll(AushContext.instance.getVars())
        return pb
    }

    /**
     * Executes piped command. Every command in piped statement start
     * as processes and after their input-output streams are piped
     */
    private fun execPipedCmd(statement: Statement.Pipe) {
        logger.info("Executing piped command: $statement")
        val pbs = statement.commands.map { getProcessBuilder(it) }
        val processes = pbs.map {
            try {
                it.start()
            } catch (e: IOException) {
                logger.severe("Error starting process: ${it.command()}")
                throw CmdExecutionError("Can't start process in pipe: ${it.command()}")
            }
        }
        val piperThreads = processes.zip(processes.slice(1..processes.size-1))
                .map { Thread(ProcessPiper(it.first, it.second)) }
        piperThreads.forEach(Thread::start)
        piperThreads.forEach(Thread::join)
        val lastProcess = processes.last()
        try {
            IOUtils.copy(lastProcess.inputStream, System.out)
        } catch (e: IOException) {
            logger.severe("Error copying last output in pipe command: ${e.message}")
            throw CmdExecutionError("Error executing pipe")
        } finally {
            lastProcess.inputStream.close()
        }
        // calculating exit code (and waiting for process termination)
        AushContext.instance.setExitCode(if (processes.map { it.waitFor() }.any { it == 0 }) 0 else 1)
    }

    private fun execAssignCmd(statement: Statement.Assign) {
        logger.info("Adding var: ${statement.varName}, value = ${statement.value}")
        AushContext.instance.addVar(statement.varName, statement.value)
        AushContext.instance.setExitCode(0)
    }
}
