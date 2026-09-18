package com.nanosplit.cli;

import com.nanosplit.util.Version;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** NanoSplit: split huge generated SQL scripts into runnable, resumable parts. */
@Command(
        name = "nanosplit",
        mixinStandardHelpOptions = true,
        version = "NanoSplit " + "1.0.0",
        description = "Splits huge generated SQL scripts (GeneXus/SSMS-style dumps) into "
                + "runnable, resumable parts, and drives their execution against SQL Server.",
        subcommands = {
                InitCommand.class,
                SplitCommand.class,
                SmokeCommand.class,
                RunCommand.class,
                StatusCommand.class,
                VerifyCommand.class,
        })
public final class Main implements Runnable {

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            System.err.println("error: " + ex.getMessage());
            if (System.getenv("NANOSPLIT_DEBUG") != null) {
                ex.printStackTrace();
            }
            return 1;
        });
        int code = cli.execute(args);
        System.exit(code);
    }

    @Override
    public void run() {
        System.out.println("NanoSplit " + Version.STRING);
        System.out.println("Run 'nanosplit --help' to see available commands: init, split, smoke, run, status, verify.");
    }
}
