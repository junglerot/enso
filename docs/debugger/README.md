---
layout: section-summary
title: Debugger
category: debugger
tags: [debugger, repl, readme]
order: 0
---

# Enso Debugger

The Enso Debugger allows amongst other things, to execute arbitrary expressions
in a given execution context - this is used to implement a debugging REPL. The
REPL can be launched when triggering a breakpoint in the code.

This folder contains all documentation pertaining to the REPL and the debugger,
which is broken up as follows:

- [**The Enso Debugger Protocol:**](./protocol.md) The protocol for the Debugger

# Chrome Developer Tools Debugger

As a well written citizen of the [GraalVM](http://graalvm.org) project the Enso
language can be used with existing tools available for the overall platform. One
of them is
[Chrome Debugger](https://www.graalvm.org/22.1/tools/chrome-debugger/) and Enso
language is fully integrated with it. Launch the `bin/enso` executable with
additional `--inspect` option and debug your Enso programs in _Chrome Developer
Tools_.

```bash
enso$ ./built-distribution/enso-engine-*/enso-*/bin/enso --inspect --run ./test/Tests/src/Data/Numbers_Spec.enso
Debugger listening on ws://127.0.0.1:9229/Wugyrg9
For help, see: https://www.graalvm.org/tools/chrome-debugger
E.g. in Chrome open: devtools://devtools/bundled/js_app.html?ws=127.0.0.1:9229/Wugyrg9
```

copy the printed URL into chrome browser and you should see:

![Chrome Debugger](chrome-debugger.png)

Step in, step over, set breakpoints, watch values of your variables as execution
of your Enso program progresses.

# Debugging Enso and Java Code at Once

Enso libraries are written in a mixture of Enso code and Java libraries.
Debugging both sides (the Java as well as Enso code) is possible with a decent
IDE.

Get [NetBeans](http://netbeans.apache.org) version 13 or newer or
[VS Code with Apache Language Server extension](https://cwiki.apache.org/confluence/display/NETBEANS/Apache+NetBeans+Extension+for+Visual+Studio+Code)
and just pass in special JVM arguments when launching the `bin/enso` launcher:

```bash
enso$ JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,address=8000 ./built-distribution/enso-engine-*/enso-*/bin/enso --run ./test/Tests/src/Data/Numbers_Spec.enso
Listening for transport dt_socket at address: 8000
```

and then _Debug/Attach Debugger_. Once connected suspend the execution and (if
the Enso language has already been started) choose the _Toggle Pause in GraalVM
Script_ button in the toolbar:

![NetBeans Debugger](java-debugger.png)

and your execution shall stop on the next `.enso` line of code. This mode allows
to debug both - the Enso code as well as Java code. The stack traces shows a
mixture of Java and Enso stack frames by default. Right-clicking on the thread
allows one to switch to plain Java view (with a way more stack frames) and back.
Analyzing low level details as well as Enso developer point of view shall be
simple with such tool.
