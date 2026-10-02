void main(String... args) {
    assert args.length == 1 : "No message provided";

    var expected = "Hello";
    var actual = args[0];
    try {
        assert Objects.equals(expected, actual) : "Failed: expected '" + expected + "', actual '" + actual + "'";
        IO.println("Passed");
    } catch (AssertionError error) {
        IO.println("Failed");
    }
}
