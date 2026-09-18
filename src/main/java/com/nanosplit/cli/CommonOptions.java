package com.nanosplit.cli;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Option;

/** Options shared by every subcommand: which config file, and ad-hoc overrides. */
public final class CommonOptions {

    @Option(names = {"-c", "--config"}, description = "Path to nanosplit.properties (default: ./nanosplit.properties)")
    public String config;

    @Option(names = "--set", description = "Override one config key, e.g. --set db.name=MyDb (repeatable)")
    public List<String> overrides = new ArrayList<String>();
}
