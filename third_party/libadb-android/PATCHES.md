# Local libadb-android 3.1.1 patch

Original: https://github.com/MuntashirAkon/libadb-android tag 3.1.1.
Upstream src/main and COPYING/LICENSES are retained, except for precisely two synchronization fixes:

1. AdbStream.read(): the empty-queue wait stops when mPendingClose is true, so a peer CLSE received with queued data cannot strand a subsequent read.
2. AdbConnection.open(): wait for the persistent initial A_OKAY state (existing volatile remote ID) or a closed stream instead of an unconditional wait. A small AdbStream.hasOpenAck() accessor supplies that condition.

No other source or protocol changes. Dependencies mirror original upstream. Pillion uses a local module instead of the JitPack binary to prevent duplicate class definitions. This is a test-only change; verify Helper / VD, loopback ADB, Stop and Reset on device.
