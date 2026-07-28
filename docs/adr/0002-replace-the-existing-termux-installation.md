# Replace the Existing Termux Installation

The customized app keeps the `com.termux` identity, shared user, and standard Termux filesystem prefix instead of attempting side-by-side installation. Release builds use a private signing key, so existing Termux data must be backed up and installations with a different signature, including plugins, must be removed or rebuilt with the same key before installation.
