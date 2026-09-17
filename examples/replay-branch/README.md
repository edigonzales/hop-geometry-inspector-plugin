# Branch replay

Record Source, Left, Right and Result in one full-cache inspection. The source
copies four rows to each branch. Select Left and Right caches and Run FROM Result.
The eight result rows must match the original result, with no source execution.
Ordering between independent incoming streams is not guaranteed.
