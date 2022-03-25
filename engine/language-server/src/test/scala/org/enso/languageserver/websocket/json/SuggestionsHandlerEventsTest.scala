package org.enso.languageserver.websocket.json

import io.circe.literal._
import org.enso.languageserver.search.Suggestions
import org.enso.languageserver.websocket.json.{SearchJsonMessages => json}
import org.enso.polyglot.{ExportedSymbol, ModuleExports}
import org.enso.polyglot.data.Tree
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.testkit.FlakySpec

import scala.collection.immutable.ListSet

class SuggestionsHandlerEventsTest extends BaseServerTest with FlakySpec {

  "SuggestionsHandlerEvents" must {

    "send suggestions database notifications" taggedAs Flaky in {
      val client = getInitialisedWsClient()

      client.send(json.acquireSuggestionsDatabaseUpdatesCapability(0))
      client.expectJson(json.ok(0))

      // add atom
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("1"),
          Vector(),
          Vector(),
          Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestions.atom,
                  Api.SuggestionAction.Add()
                ),
                Vector()
              )
            )
          )
        )
      )
      client.expectJson(json"""
        { "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Add",
                "id" : 1,
                "suggestion" : {
                  "type" : "atom",
                  "module" : "Test.Main",
                  "name" : "MyType",
                  "arguments" : [
                    {
                      "name" : "a",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    }
                  ],
                  "returnType" : "MyAtom",
                  "documentation" : " PRIVATE\n\n A key-value store. This type assumes all keys are pairwise comparable,\n using the `<`, `>` and `==` operators.\n\n Arguments:\n - one: The first.\n - two_three: The *second*.\n\n ? Info\n   Here is a thing.",
                  "documentationHtml" : "<html><body><div class=\"enso docs\"><div><div class=\"doc-title-container\"><div class=\"doc-title-name\">MyType</div><div class=\"tags\"><p class=\"tag\"><span class=\"private\">PRIVATE</span></p></div></div><div class=\"synopsis\"><p> </p></div><div class=\"body\"><p>A key-value store. This type assumes all keys are pairwise comparable, using the <code>&lt;</code>, <code>&gt;</code> and <code>==</code> operators. </p><p>Arguments: <ul><li>one: The first.</li><li>two<div class=\"Unclosed\"><i>three: The <b>second</b>.</i></div></li></ul> </p><div class=\"info\"><div class=\"summary\">Info</div> Here is a thing.</div></div></div></div></body></html>",
                  "documentationSections" : [
                    {
                      "type" : "tag",
                      "name" : "PRIVATE",
                      "body" : ""
                    },
                    {
                      "type" : "paragraph",
                      "body" : "A key-value store. This type assumes all keys are pairwise comparable, using the <code>&lt;</code>, <code>&gt;</code> and <code>==</code> operators. "
                    },
                    {
                      "type" : "keyed",
                      "key" : "Arguments",
                      "body" : " <ul><li>one: The first.</li><li>two_three: The <b>second</b>.</li></ul> "
                    },
                    {
                      "type" : "marked",
                      "mark" : "Info",
                      "header" : "Info",
                      "body" : " Here is a thing."
                    }
                  ]
               }
             }
           ],
            "currentVersion" : 1
          }
        }
        """)

      // add method
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("2"),
          Vector(),
          Vector(),
          Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestions.atom,
                  Api.SuggestionAction.Modify()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestions.method,
                      Api.SuggestionAction.Add()
                    ),
                    Vector()
                  )
                )
              )
            )
          )
        )
      )
      client.expectJson(json"""
        { "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Add",
                "id" : 2,
                "suggestion" : {
                  "type" : "method",
                  "externalId" : "ea9d7734-26a7-4f65-9dd9-c648eaf57d63",
                  "module" : "Test.Main",
                  "name" : "foo",
                  "arguments" : [
                    {
                      "name" : "this",
                      "reprType" : "MyType",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "foo",
                      "reprType" : "Number",
                      "isSuspended" : false,
                      "hasDefault" : true,
                      "defaultValue" : "42"
                    }
                  ],
                  "selfType" : "MyType",
                  "returnType" : "Number",
                  "documentation" : "Lovely",
                  "documentationHtml" : "<html><body><div class=\"enso docs\"><div><div class=\"doc-title-container\"><div class=\"doc-title-name\">foo</div></div><div class=\"synopsis\"><p>Lovely</p></div></div></div></body></html>",
                  "documentationSections" : [
                    {
                      "type" : "paragraph",
                      "body" : "Lovely"
                    }
                  ]
                }
              }
            ],
            "currentVersion" : 2
          }
        }
        """)

      // add function
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("3"),
          Vector(),
          Vector(),
          Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestions.atom,
                  Api.SuggestionAction.Modify()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestions.method,
                      Api.SuggestionAction.Modify()
                    ),
                    Vector(
                      Tree.Node(
                        Api.SuggestionUpdate(
                          Suggestions.function,
                          Api.SuggestionAction.Add()
                        ),
                        Vector()
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
      client.expectJson(json"""
        { "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Add",
                "id" : 3,
                "suggestion" : {
                  "type" : "function",
                  "externalId" : "78d452ce-ed48-48f1-b4f2-b7f45f8dff89",
                  "module" : "Test.Main",
                  "name" : "print",
                  "arguments" : [
                    {
                      "name" : "a",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "b",
                      "reprType" : "Any",
                      "isSuspended" : true,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "c",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : true,
                      "defaultValue" : "C"
                    }
                  ],
                  "returnType" : "IO",
                  "scope" : {
                    "start" : {
                      "line" : 1,
                      "character" : 9
                    },
                    "end" : {
                      "line" : 1,
                      "character" : 22
                    }
                  }
                }
              }
            ],
            "currentVersion" : 3
          }
        }
      """)

      // add local
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("4"),
          Vector(),
          Vector(),
          Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestions.atom,
                  Api.SuggestionAction.Modify()
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestions.method,
                      Api.SuggestionAction.Modify()
                    ),
                    Vector(
                      Tree.Node(
                        Api.SuggestionUpdate(
                          Suggestions.function,
                          Api.SuggestionAction.Modify()
                        ),
                        Vector(
                          Tree.Node(
                            Api.SuggestionUpdate(
                              Suggestions.local,
                              Api.SuggestionAction.Add()
                            ),
                            Vector()
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
      client.expectJson(json"""
        { "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Add",
                "id" : 4,
                "suggestion" : {
                  "type" : "local",
                  "externalId" : "dc077227-d9b6-4620-9b51-792c2a69419d",
                  "module" : "Test.Main",
                  "name" : "x",
                  "returnType" : "Number",
                  "scope" : {
                    "start" : {
                      "line" : 21,
                      "character" : 0
                    },
                    "end" : {
                      "line" : 89,
                      "character" : 0
                    }
                  }
                }
              }
            ],
            "currentVersion" : 4
          }
        }
        """)

      // get suggestions database
      client.send(json.getSuggestionsDatabase(3))
      client.expectJson(json"""
        { "jsonrpc" : "2.0",
          "id" : 3,
          "result" : {
            "entries" : [
              {
                "id" : 3,
                "suggestion" : {
                  "type" : "function",
                  "externalId" : ${Suggestions.function.externalId.get},
                  "module" : "Test.Main",
                  "name" : "print",
                  "arguments" : [
                    {
                      "name" : "a",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "b",
                      "reprType" : "Any",
                      "isSuspended" : true,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "c",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : true,
                      "defaultValue" : "C"
                    }
                  ],
                  "returnType" : "IO",
                  "scope" : {
                    "start" : {
                      "line" : 1,
                      "character" : 9
                    },
                    "end" : {
                      "line" : 1,
                      "character" : 22
                    }
                  }
                }
              },
              {
                "id" : 4,
                "suggestion" : {
                  "type" : "local",
                  "externalId" : ${Suggestions.local.externalId.get},
                  "module" : "Test.Main",
                  "name" : "x",
                  "returnType" : "Number",
                  "scope" : {
                    "start" : {
                      "line" : 21,
                      "character" : 0
                    },
                    "end" : {
                      "line" : 89,
                      "character" : 0
                    }
                  }
                }
              },
              {
                "id" : 2,
                "suggestion" : {
                  "type" : "method",
                  "externalId" : ${Suggestions.method.externalId.get},
                  "module" : "Test.Main",
                  "name" : "foo",
                  "arguments" : [
                    {
                      "name" : "this",
                      "reprType" : "MyType",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    },
                    {
                      "name" : "foo",
                      "reprType" : "Number",
                      "isSuspended" : false,
                      "hasDefault" : true,
                      "defaultValue" : "42"
                    }
                  ],
                  "selfType" : "MyType",
                  "returnType" : "Number",
                  "documentation" : "Lovely",
                  "documentationHtml" : "<html><body><div class=\"enso docs\"><div><div class=\"doc-title-container\"><div class=\"doc-title-name\">foo</div></div><div class=\"synopsis\"><p>Lovely</p></div></div></div></body></html>",
                  "documentationSections" : [
                    {
                      "type" : "paragraph",
                      "body" : "Lovely"
                    }
                  ]
                }
              },
              {
                "id" : 1,
                "suggestion" : {
                  "type" : "atom",
                  "module" : "Test.Main",
                  "name" : "MyType",
                  "arguments" : [
                    {
                      "name" : "a",
                      "reprType" : "Any",
                      "isSuspended" : false,
                      "hasDefault" : false,
                      "defaultValue" : null
                    }
                  ],
                  "returnType" : "MyAtom",
                  "documentation" : " PRIVATE\n\n A key-value store. This type assumes all keys are pairwise comparable,\n using the `<`, `>` and `==` operators.\n\n Arguments:\n - one: The first.\n - two_three: The *second*.\n\n ? Info\n   Here is a thing.",
                  "documentationHtml" : "<html><body><div class=\"enso docs\"><div><div class=\"doc-title-container\"><div class=\"doc-title-name\">MyType</div><div class=\"tags\"><p class=\"tag\"><span class=\"private\">PRIVATE</span></p></div></div><div class=\"synopsis\"><p> </p></div><div class=\"body\"><p>A key-value store. This type assumes all keys are pairwise comparable, using the <code>&lt;</code>, <code>&gt;</code> and <code>==</code> operators. </p><p>Arguments: <ul><li>one: The first.</li><li>two<div class=\"Unclosed\"><i>three: The <b>second</b>.</i></div></li></ul> </p><div class=\"info\"><div class=\"summary\">Info</div> Here is a thing.</div></div></div></div></body></html>",
                  "documentationSections" : [
                    {
                      "type" : "tag",
                      "name" : "PRIVATE",
                      "body" : ""
                    },
                    {
                      "type" : "paragraph",
                      "body" : "A key-value store. This type assumes all keys are pairwise comparable, using the <code>&lt;</code>, <code>&gt;</code> and <code>==</code> operators. "
                    },
                    {
                      "type" : "keyed",
                      "key" : "Arguments",
                      "body" : " <ul><li>one: The first.</li><li>two_three: The <b>second</b>.</li></ul> "
                    },
                    {
                      "type" : "marked",
                      "mark" : "Info",
                      "header" : "Info",
                      "body" : " Here is a thing."
                    }
                  ]
                }
              }
            ],
            "currentVersion" : 4
          }
        }
        """)

      // update items
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("5"),
          Vector(),
          Vector(),
          Tree.Root(
            Vector(
              Tree.Node(
                Api.SuggestionUpdate(
                  Suggestions.atom,
                  Api.SuggestionAction.Modify(
                    arguments = Some(
                      Seq(
                        Api.SuggestionArgumentAction
                          .Modify(0, reprType = Some("A")),
                        Api.SuggestionArgumentAction
                          .Add(1, Suggestions.function.arguments(1))
                      )
                    )
                  )
                ),
                Vector(
                  Tree.Node(
                    Api.SuggestionUpdate(
                      Suggestions.method,
                      Api.SuggestionAction.Modify()
                    ),
                    Vector(
                      Tree.Node(
                        Api.SuggestionUpdate(
                          Suggestions.function,
                          Api.SuggestionAction.Modify(
                            externalId = Some(None)
                          )
                        ),
                        Vector(
                          Tree.Node(
                            Api.SuggestionUpdate(
                              Suggestions.local,
                              Api.SuggestionAction.Modify(
                                scope = Some(Suggestions.function.scope)
                              )
                            ),
                            Vector()
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
      client.expectJson(json"""
        {
          "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Modify",
                "id" : 1,
                "arguments" : [
                  {
                    "type" : "Modify",
                    "index" : 0,
                    "reprType" : {
                      "tag" : "Set",
                      "value" : "A"
                    }
                  },
                  {
                    "type" : "Add",
                    "index" : 1,
                    "argument" : {
                      "name" : "b",
                      "reprType" : "Any",
                      "isSuspended" : true,
                      "hasDefault" : false,
                      "defaultValue" : null
                    }
                  }
                ]
              },
              {
                "type" : "Modify",
                "id" : 3,
                "externalId" : {
                  "tag" : "Remove",
                  "value" : null
                }
              },
              {
                "type" : "Modify",
                "id" : 4,
                "scope" : {
                  "tag" : "Set",
                  "value" : {
                    "start" : {
                      "line" : 1,
                      "character" : 9
                    },
                    "end" : {
                      "line" : 1,
                      "character" : 22
                    }
                  }
                }
              }
            ],
            "currentVersion" : 7
          }
        }
        """)

      // update exports
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("6"),
          Vector(),
          Vector(
            Api.ExportsUpdate(
              ModuleExports(
                "Foo.Bar",
                ListSet(
                  ExportedSymbol
                    .Atom(Suggestions.atom.module, Suggestions.atom.name)
                )
              ),
              Api.ExportsAction.Add()
            )
          ),
          Tree.Root(Vector())
        )
      )
      client.expectJson(json"""
          {
            "jsonrpc" : "2.0",
            "method" : "search/suggestionsDatabaseUpdates",
            "params" : {
              "updates" : [
                {
                  "type" : "Modify",
                  "id" : 1,
                  "reexport" : {
                    "tag" : "Set",
                    "value" : "Foo.Bar"
                  }
                }
              ],
              "currentVersion" : 8
            }
          }
        """)

      // remove items
      system.eventStream.publish(
        Api.SuggestionsDatabaseModuleUpdateNotification(
          "Foo.Main",
          versionCalculator.evalVersion("7"),
          Vector(Api.SuggestionsDatabaseAction.Clean(Suggestions.atom.module)),
          Vector(),
          Tree.Root(Vector())
        )
      )
      client.expectJson(json"""
        {
          "jsonrpc" : "2.0",
          "method" : "search/suggestionsDatabaseUpdates",
          "params" : {
            "updates" : [
              {
                "type" : "Remove",
                "id" : 1
              },
              {
                "type" : "Remove",
                "id" : 2
              },
              {
                "type" : "Remove",
                "id" : 3
              },
              {
                "type" : "Remove",
                "id" : 4
              }
            ],
            "currentVersion" : 8
          }
        }
        """)

    }
  }

}
