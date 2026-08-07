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
            count=0

            printf 'pid\tppid\tuid\tstate\tname\tcmdline\n'

            matches_filter() {
              pid="${'$'}1"
              [ -n "${'$'}pid" ] || return 1
              proc=/proc/"${'$'}pid"
              [ -d "${'$'}proc" ] || return 1

              name=''
              IFS= read -r name < "${'$'}proc/comm" 2>/dev/null || true
              cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | tr '\t\r\n' '   ')

              case "${'$'}name ${'$'}cmdline" in
                *"${'$'}filter"*) return 0 ;;
                *) return 1 ;;
              esac
            }

            emit_pid() {
              pid="${'$'}1"
              [ -n "${'$'}pid" ] || return 0
              [ "${'$'}pid" = "${'$'}self_pid" ] && return 0
              proc=/proc/"${'$'}pid"
              [ -d "${'$'}proc" ] || return 0

              fields=${'$'}(awk '
                /^Name:/ { name = ${'$'}2 }
                /^State:/ { state = ${'$'}2 }
                /^PPid:/ { ppid = ${'$'}2 }
                /^Uid:/ { uid = ${'$'}2 }
                END {
                  if (name != "" && ppid != "" && uid != "") {
                    printf "%s %s %s %s", ppid, uid, state, name
                  }
                }
              ' "${'$'}proc/status" 2>/dev/null) || return 0
              [ -n "${'$'}fields" ] || return 0

              set -- ${'$'}fields
              ppid=${'$'}{1:-}
              uid=${'$'}{2:-}
              state=${'$'}{3:-}
              name=${'$'}{4:-}

              cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | tr '\t\r\n' '   ')

              if [ -n "${'$'}filter" ]; then
                case "${'$'}name ${'$'}cmdline" in
                  *"${'$'}filter"*) ;;
                  *) return 0 ;;
                esac
              fi

              printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
                "${'$'}pid" "${'$'}ppid" "${'$'}uid" "${'$'}state" "${'$'}name" "${'$'}cmdline"
              count=${'$'}((count + 1))
            }

            if [ -n "${'$'}filter" ]; then
              # KSU/Magisk su -c may exec the shell directly. In that case PPID is the calling app
              # itself, so PPID is a legitimate target and must never be excluded as helper noise.
              parent_candidate=''
              parent_matched=0
              if [ "${'$'}parent_pid" -gt 0 ] 2>/dev/null && matches_filter "${'$'}parent_pid"; then
                parent_candidate="${'$'}parent_pid"
                parent_matched=1
              fi

              # Filtered discovery is authoritative from procfs. Android ps column layouts vary
              # between platform/toybox versions, while /proc/<pid>/cmdline keeps the real argv0.
              candidate_pids=${'$'}(
                {
                  [ -n "${'$'}parent_candidate" ] && printf '%s\n' "${'$'}parent_candidate"
                  pidof "${'$'}filter" 2>/dev/null || true
                  grep -l -F -- "${'$'}filter" /proc/[0-9]*/cmdline 2>/dev/null || true
                  grep -l -F -- "${'$'}filter" /proc/[0-9]*/comm 2>/dev/null || true
                } | sed -n \
                  -e 's#^/proc/\([0-9][0-9]*\)/cmdline${'$'}#\1#p' \
                  -e 's#^/proc/\([0-9][0-9]*\)/comm${'$'}#\1#p' \
                  -e '/^[0-9][0-9 ]*${'$'}/p' \
                  | tr ' ' '\n' \
                  | sed -n '/^[0-9][0-9]*${'$'}/p' \
                  | sort -n -u
              )
              candidate_count=${'$'}(printf '%s\n' "${'$'}candidate_pids" | awk 'NF { count++ } END { print count + 0 }')

              for pid in ${'$'}candidate_pids; do
                emit_pid "${'$'}pid"
                [ "${'$'}count" -ge "${'$'}max_count" ] && break
              done

              printf 'AUTOCRACK_DISCOVERY filter=%s self_pid=%s parent_pid=%s parent_matched=%s candidates=%s emitted=%s\n' \
                "${'$'}filter" "${'$'}self_pid" "${'$'}parent_pid" \
                "${'$'}parent_matched" "${'$'}candidate_count" "${'$'}count" >&2
              exit 0
            fi

            # Unfiltered discovery can use ps as a cheap PID source, but procfs remains the source
            # of truth for identity fields and command line parsing.
            candidate_pids=${'$'}(ps -A -o PID= 2>/dev/null | sed -n '/^[[:space:]]*[0-9][0-9]*[[:space:]]*${'$'}/p' | tr -d ' ')
            if [ -z "${'$'}candidate_pids" ]; then
              candidate_pids=${'$'}(for proc in /proc/[0-9]*; do [ -d "${'$'}proc" ] && printf '%s\n' "${'$'}{proc##*/}"; done)
            fi

            for pid in ${'$'}candidate_pids; do
              emit_pid "${'$'}pid"
              [ "${'$'}count" -ge "${'$'}max_count" ] && break
            done
        """.trimIndent()

        return listOf(suPath, "-c", shellCommand)
    }

    private const val MAX_PROCESS_COUNT = 2_000
}
