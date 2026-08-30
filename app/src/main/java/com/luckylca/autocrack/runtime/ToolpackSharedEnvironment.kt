package com.luckylca.autocrack.runtime

/**
 * Makes installed toolpacks behave like packages in one shared Debian environment.
 *
 * Toolpacks remain isolated on disk for install/update/uninstall/version verification, while
 * conventional language payload directories are discovered at shell start and composed into the
 * process environment. Native lib/ directories are deliberately not added to LD_LIBRARY_PATH:
 * mixing unrelated toolpack ABIs globally would make otherwise independent CLI tools fragile.
 */
internal object ToolpackSharedEnvironment {
    internal const val ACTIVE_PACK_ROOT = "/opt/autocrack/toolpacks/active"

    fun shellBootstrap(activePackRoot: String = ACTIVE_PACK_ROOT): String = """
        autoc_prepend_toolpack_path() {
          autoc_current=${'$'}1
          autoc_entry=${'$'}2
          if [ -z "${'$'}autoc_current" ]; then
            printf '%s' "${'$'}autoc_entry"
          else
            printf '%s:%s' "${'$'}autoc_entry" "${'$'}autoc_current"
          fi
        }

        AUTOC_TOOLPACK_ACTIVE_ROOT=${ShellEscaper.quote(activePackRoot)}
        for autoc_pack in "${'$'}AUTOC_TOOLPACK_ACTIVE_ROOT"/*; do
          [ -L "${'$'}autoc_pack" ] && [ -d "${'$'}autoc_pack" ] || continue

          autoc_dir=${'$'}autoc_pack/python
          if [ -d "${'$'}autoc_dir" ]; then
            PYTHONPATH=${'$'}(autoc_prepend_toolpack_path "${'$'}{PYTHONPATH:-}" "${'$'}autoc_dir")
          fi

          for autoc_dir in "${'$'}autoc_pack/node_modules" "${'$'}autoc_pack/lib/node_modules"; do
            [ -d "${'$'}autoc_dir" ] || continue
            NODE_PATH=${'$'}(autoc_prepend_toolpack_path "${'$'}{NODE_PATH:-}" "${'$'}autoc_dir")
          done

          for autoc_dir in "${'$'}autoc_pack/java" "${'$'}autoc_pack/lib/java"; do
            [ -d "${'$'}autoc_dir" ] || continue
            CLASSPATH=${'$'}(autoc_prepend_toolpack_path "${'$'}{CLASSPATH:-}" "${'$'}autoc_dir")
            CLASSPATH=${'$'}(autoc_prepend_toolpack_path "${'$'}{CLASSPATH:-}" "${'$'}autoc_dir/*")
          done
        done

        export PYTHONPATH NODE_PATH CLASSPATH
        unset AUTOC_TOOLPACK_ACTIVE_ROOT autoc_pack autoc_dir autoc_current autoc_entry
        unset -f autoc_prepend_toolpack_path
    """.trimIndent()
}
