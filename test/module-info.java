/// The named runner for Tuul's tests. Test classes themselves are patched into
/// `tuul` so tests may exercise package-private production contracts without a
/// split package. Only this runner is a separate module.
module tuul.test {
    requires tuul;
}
