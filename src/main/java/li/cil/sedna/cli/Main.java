package li.cil.sedna.cli;

import li.cil.sedna.Sedna;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "sedna-cli",
    mixinStandardHelpOptions = true,
    subcommands = {RiscVCommand.class, CpmCommand.class},
    description = "Runs a Sedna virtual machine. Defaults to the RISC-V machine.")
public final class Main implements Callable<Integer> {
    public static void main(final String[] args) {
        Sedna.initialize();
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        return new RiscVCommand().call();
    }
}
