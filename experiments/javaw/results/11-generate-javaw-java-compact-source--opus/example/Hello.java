void main(String[] args) {
    var words = Arrays.stream(args).map(a -> "[" + a + "]").toList();
    IO.println("Hello " + String.join(" ", words) + " (app.version=" + System.getProperty("app.version", "1") + ")");
}
