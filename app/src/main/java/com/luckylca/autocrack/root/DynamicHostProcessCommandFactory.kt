package com.luckylca.autocrack.root

object DynamicHostProcessCommandFactory {
    fun build(
        suPath: String,
        filter: String,
        maxCount: Int,
    ): List<String> {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require(maxCount in 1..MAX_PROCESS_COUNT) {
            "Process count must be between 1 and $MAX_PROCESS_COUNT"
        }
        val normalizedFilter = filter.trim()
        val quotedFilter = RootToolCommandFactory.shellQuote(normalizedFilter)

        val shellCommand = """
            filter=$quotedFilter
            max_count=$maxCount
            self_pid=${'$'}${'$'}
            parent_pid=${'$'}{PPID:-0}
            export filter max_count self_pid parent_pid

            printf 'pid\tppid\tuid\tstate\tname\tcmdline\n'

            emit_ps() {
              ps -A -n -ww -o PID,PPID,UID,STAT,NAME,ARGS 2>/dev/null && return 0
              ps -A -n -o PID,PPID,UID,STAT,NAME,ARGS 2>/dev/null
            }

            if emit_ps >/dev/null 2>&1; then
              emit_ps | awk '
                NR == 1 { next }
                {
                  pid = ${'$'}1
                  if (pid == ENVIRON["self_pid"] || pid == ENVIRON["parent_pid"]) next
                  line = ${'$'}0
                  wanted = ENVIRON["filter"]
                  if (wanted != "" && index(line, wanted) == 0) next
                  cmdline = ""
                  for (index = 6; index <= NF; index++) {
                    cmdline = cmdline (index == 6 ? "" : " ") ${'$'}index
                  }
                  printf "%s\t%s\t%s\t%s\t%s\t%s\n", ${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, cmdline
                  count++
                  if (count >= (ENVIRON["max_count"] + 0)) exit
                }
              '
              exit 0
            fi

            count=0
            for proc in /proc/[0-9]*; do
              [ -d "${'$'}proc" ] || continue
              pid=${'$'}{proc##*/}
              [ "${'$'}pid" = "${'$'}self_pid" ] && continue
              [ "${'$'}pid" = "${'$'}parent_pid" ] && continue

              name=''
              IFS= read -r name < "${'$'}proc/comm" 2>/dev/null || continue
              cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | tr '\t\r\n' '   ')

              if [ -n "${'$'}filter" ]; then
                case "${'$'}name ${'$'}cmdline" in
                  *"${'$'}filter"*) ;;
                  *) continue ;;
                esac
              fi

              fields=${'$'}(awk '
                /^State:/ { state = ${'$'}2 }
                /^PPid:/ { ppid = ${'$'}2 }
                /^Uid:/ { uid = ${'$'}2 }
                END { if (ppid != "" && uid != "") print ppid, uid, state }
              ' "${'$'}proc/status" 2>/dev/null) || continue
              [ -n "${'$'}fields" ] || continue
              set -- ${'$'}fields
              ppid=${'$'}{1:-}
              uid=${'$'}{2:-}
              state=${'$'}{3:-}

              printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
                "${'$'}pid" "${'$'}ppid" "${'$'}uid" "${'$'}state" "${'$'}name" "${'$'}cmdline"
              count=${'$'}((count + 1))
              [ "${'$'}count" -ge "${'$'}max_count" ] && break
            done
        """.trimIndent()

        return listOf(suPath, "-c", shellCommand)
    }

    private const val MAX_PROCESS_COUNT = 2_000
}
