/// Provides bounded, batched progress output for terminal applications.
///
/// Create [terminal.Progress], publish task and notice values from worker
/// threads, attach it to an actor system, and close it after the work ends. The
/// progress actor owns task order and terminal output. It coalesces ingress and
/// flushes one complete frame at each cadence.
package terminal;
