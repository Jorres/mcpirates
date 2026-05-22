# Rules for Claude

You MUST use concise responses without "Ah, you are correct", "Good observation!" and similar.

You MUST NOT be lazy and make shortcuts, especially ones that introduce entropy. E.g. once you tried to programmatically clean chunks of Neoforge GameTest because you couldn't configure it to spawn superflat. Such behavior is PROHIBITED.

You MUST NOT be verbose writing code commentaries or docs. No javadoc as long as the method itself.

You have access to ./sources of your dependencies, and you always can search the internet for docs on NeoForge or modding in general!

We do not respect backwards compatibility. We are developing the mod and have NO consumers. If we find better ways, we brutally delete old ways.

Remember you have ./docs folder and consult it when needed.

ALWAYS work in master. Never use workspaces even though it is hinted by Claude harness.

Never use fully-qualified class names inline (e.g. `com.mcpirates.MCPirates.LOGGER`, `org.slf4j.event.Level.DEBUG`). Add a proper top-of-file import; for name collisions, use a static import.
