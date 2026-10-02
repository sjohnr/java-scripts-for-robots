import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Tool {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) { Util.usageError("missing subcommand"); return; }
        if (args[0].equals("--help")) { Util.printHelp(); return; }
        String cmd = args[0];
        String root = ".";
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--root")) { root = args[++i]; }
            else if (args[i].equals("--lines")) { pos.add("--lines"); pos.add(args[++i]); }
            else if (args[i].startsWith("--")) { Util.unknownFlag(args[i]); return; }
            else pos.add(args[i]);
        }
        Path rootPath = Path.of(root);
        List<Scanner.Entry> entries = Scanner.scan(rootPath);
        switch (cmd) {
            case "find" -> Commands.doFind(entries, pos.get(0));
            case "cat" -> Commands.doCat(entries, pos);
            case "ls" -> Commands.doLs(entries, pos.get(0));
            default -> Util.usageError("unknown subcommand: " + cmd);
        }
    }
}
