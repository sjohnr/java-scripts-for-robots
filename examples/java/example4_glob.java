// #region all
// #region main
void main(String... args) throws IOException {
    assert args.length == 1 : "Please provide a glob pattern (e.g. json/*.json, .m2/repository/**.jar)";
    var glob = args[0].replace("~", System.getProperty("user.home"));
    var files = findFiles(glob);
    files.forEach(IO::println);
}
// #endregion main

List<String> findFiles(String glob) throws IOException {
    // Split at the last '/' before the first '*' into a base directory and a pattern
    var slash = glob.lastIndexOf('/', glob.indexOf('*'));
    var dir = Path.of(slash < 0 ? "." : glob.substring(0, slash));
    var pattern = glob.substring(slash + 1);
    assert Files.isDirectory(dir) : "Directory not found: " + dir;

    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    try (var paths = Files.walk(dir)) {
        return paths.filter(Files::isRegularFile)
            .filter(path -> matcher.matches(dir.relativize(path)))
            .map(Path::toString)
            .toList();
    }
}
// #endregion all
