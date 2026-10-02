///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//RUNTIME_OPTIONS -ea

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

// #region class
@Command(name = "Argparse", mixinStandardHelpOptions = true, description = "Parse arguments with picocli.")
class Argparse implements Runnable {
    @Option(names = {"-P", "--port"}, required = true, description = "Port to listen on.") int port;

    @Option(names = "--param1", required = true, description = "First parameter.") String param1;

    @Option(names = "--param2", required = true, description = "Second parameter.") int param2;

    @Option(names = "--enable-featureX", description = "Enable feature X.") boolean enableFeatureX;

    @Override
    public void run() {
        IO.println("port           = " + port);
        IO.println("param1         = " + param1);
        IO.println("param2         = " + param2);
        IO.println("enableFeatureX = " + enableFeatureX);
    }
}

// #region main
void main(String... args) {
    System.exit(new CommandLine(new Argparse()).execute(args));
}
// #endregion main
// #endregion class
