// v11 결정 2의 「ProcessHandle로 argv --job= · java」가 이 기계에서 남의 JVM argv를 읽는가 · 판정 불가(빈 arguments)가 몇인가
public class PH {
  public static void main(String[] a) {
    long self = ProcessHandle.current().pid();
    int java = 0, argsEmpty = 0, cmdEmpty = 0, all = 0, jobMatch = 0;
    for (ProcessHandle p : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
      all++;
      ProcessHandle.Info i = p.info();
      String cmd = i.command().orElse(null);
      if (cmd == null) { cmdEmpty++; continue; }
      if (!cmd.endsWith("/java")) continue;
      java++;
      var args = i.arguments();
      if (args.isEmpty()) { argsEmpty++; System.out.println("ARGS_EMPTY pid=" + p.pid() + " user=" + i.user().orElse("?") + " self=" + (p.pid()==self)); continue; }
      String line = String.join(" ", args.get());
      boolean m = line.matches(".*(^|\\s)--job=.*") || line.contains("spring.profiles.active=") && line.contains("bootstrap");
      if (m) jobMatch++;
      System.out.println("pid=" + p.pid() + " self=" + (p.pid()==self) + " nargs=" + args.get().length + " match=" + m + " head=" + line.substring(0, Math.min(120, line.length())));
    }
    System.out.println("all=" + all + " commandEmpty=" + cmdEmpty + " java=" + java + " argsEmpty=" + argsEmpty + " jobMatch=" + jobMatch);
  }
}
