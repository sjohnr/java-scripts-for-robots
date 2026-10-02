void main(String[] args) {
    if (args.length > 0 && args[0].equals("--help")) {
        IO.println("usage: noop");
        IO.println("prints \"ok\" and exits 0");
        return;
    }
    IO.println("ok");
}
