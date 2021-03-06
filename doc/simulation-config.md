## Tips for simulation environment configuration

After setting up Hadoop environment, we have to configure
simulation environment. Here are the needed files:

**`nodes`**

List of physical machines to run the experiment.
The goal of simulator is to evaluate target node in large scale
with limited resources. We only need a few machines to
simulate the large scale experiments.

**`conf`**

Specify IPs of different components including target node
(e.g. NameNode), central controller and simulator pools (stub nodes).
Besides, some experiment parameters are set in this file as well,
such as "parallel" to indicate number of mappers to launch in a
batch on each NodeManager.

**`joblist`**

List of benchmark jobs. For example:
```
conf/nn-terasort-dn50-c8/workload
conf/nn-terasort-dn50-c8/workload
```

The above list indicates that we launch two jobs
of **`nn-terasort-dn50-c8`**.

**`script-ts, script-rm`**

These two scripts are generated by PatternMiner, which specify the 
execution order across different components for TeraSort against 
NameNode and ResourceManager, respectively. Within simulator,
central controller reads these scripts and coordinates
the execution orders of different components.

Components will be executed in order, and our simulator supports dependence.
For example, there are three consecutive lines within script-ts:

```
mapper-0001-01.all mapper-0001-01.first
am-0001-01.[0,0,29@0,0,30] mapper-0001-01.last 
am-0001-01.[0,0,31@2,0,43] am-0001-01.[0,0,31@2,0,43]
```

All mappers (**`mapper-0001-01`**) will be launched.
After first mapper finishes, AppMaster (**`am-0001-01`**)
will execute RPCs from index **`0,0,29`** to index **`0,0,30`**.
After last mapper finishes, AppMaster will execute the remaining
RPCs from index **`0,0,31`** to **`2,0,43`**.

