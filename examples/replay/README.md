# Replay

Open [replay-cache.hpl](replay-cache.hpl) in Hop Desktop. Data is embedded in the Data Grid; no paths, network sources or extra plugins are required.

Capture Source MAIN and Result MAIN with ALL. Close and reopen the cache manager. From Result choose the Source cache and Run FROM. Result receives the four cached rows; Source is replaced and does not execute.

The same cache flow can be used with a Vector Reader: choose *Inspect data...*, select
*Record complete disk cache* and the reader's `OUTPUT MAIN` stream. *Run TO* on the Vector Reader
then executes the reader normally and records its output, even when no cache exists yet.
