import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.util.logging.LogManager;
import java.util.logging.LoggingMXBean;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.crsh.cmdline.spi.Completer;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy
import org.crsh.cmdline.annotations.Usage
import org.crsh.cmdline.annotations.Option
import org.crsh.cmdline.annotations.Required
import org.crsh.cmdline.annotations.Argument
import org.crsh.command.CRaSHCommand
import org.crsh.cmdline.annotations.Man
import org.crsh.cmdline.annotations.Command
import org.crsh.command.InvocationContext

import org.crsh.cmdline.completers.EnumCompleter
import org.crsh.cmdline.spi.Completion
import org.crsh.command.PipeCommand;

@Usage("logging commands")
public class log extends CRaSHCommand implements Completer {

  @Usage("send a message to a logger")
  @Man("""\
The send command log one or several loggers with a specified message. For instance the following impersonates
the javax.management.mbeanserver class and send a message on its own logger.

#% log send -m hello javax.management.mbeanserver

Send is a <Logger, Void> command, it can log messages to consumed log objects:

% log ls | log send -m hello -l warn""")
  @Command
  public PipeCommand<Logger, Object> send(@MsgOpt String msg, @LoggerArg String name, @LevelOpt Level level) {
    level = level ?: Level.info;
    return new PipeCommand<Logger, Object>() {
      @Override
      void open() {
        if (!isPiped()) {
          if (name != null) {
            def logger = LoggerFactory.getLogger(name);
            level.log(logger, msg);
          }
        }
      }
      @Override
      void provide(Logger element) {
        level.log(element, msg);
      }
    }
  }

  private Collection<String> getLoggers() {
    def names = [] as Set;
    def factory = LoggerFactory.ILoggerFactory;
    def factoryName = factory.class.simpleName;
    if (factoryName.equals("JDK14LoggerFactory")) {
      // JDK
      LogManager mgr = LogManager.logManager;
      LoggingMXBean mbean = mgr.loggingMXBean;

      // Add the known names
      names.addAll(mbean.loggerNames);

      // This is a trick to get the logger names per web application in Tomcat environment
      try {
        def registryClass = Thread.currentThread().contextClassLoader.loadClass("org.apache.tomcat.util.modeler.Registry");
        def getRegistry = registry.getMethod("getRegistry");
        def registry = registry.invoke(null);
        def server = registry.MBeanServer;
        ObjectName on = new ObjectName("*:j2eeType=WebModule,*");
        def res = server.queryNames(on, null).each {
          def loader = server.getAttribute(it, "loader");
          def oldCL = Thread.currentThread().contextClassLoader;
          try {
            Thread.currentThread().contextClassLoader = loader.classLoader;
            names.addAll(mbean.loggerNames);
            } finally {
            Thread.currentThread().contextClassLoader = oldCL;
          }
        }
      } catch (Exception ignore) {
      }
    } else if (factoryName.equals("JBossLoggerFactory")) {
      // JBoss AS
      def f = factory.class.getDeclaredField("loggerMap");
      f.accessible = true;
      def loggers = f.get(factory);
      names.addAll(loggers.keySet());
    } else {
      System.out.println("Implement log lister for implementation " + factory.getClass().getName());
    }

    //
    return names;
  }

  @Usage("list the available loggers")
  @Man("""\
The logls command list all the available loggers., for instance:

% logls
org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/].[default]
org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/eXoGadgetServer].[concat]
org.apache.catalina.core.ContainerBase.[Catalina].[localhost].[/dashboard].[jsp]
...

The -f switch provides filtering with a Java regular expression

% logls -f javax.*
javax.management.mbeanserver
javax.management.modelmbean

The logls command is a <Void,Logger> command, therefore any logger produced can be consumed.""")

  @Command
  public void ls(InvocationContext<Logger> context, @FilterOpt String filter) {

    // Regex filter
    def pattern = Pattern.compile(filter ?: ".*");

    //
    loggers.each {
       def matcher = it =~ pattern;
       if (matcher.matches()) {
         def logger = LoggerFactory.getLogger(it);
         context.provide(logger);
       }
    }
  }

  @Usage("create one or several loggers")
  @Command
  public void add(InvocationContext<Logger> context, @LoggerArg List<String> names) {
    names.each {
      if (it.string.length() > 0) {
        Logger logger = LoggerFactory.getLogger(it);
        if (logger != null) {
          context.provide(logger);
        }
      }
    }
  }

  @Man("""\
The set command sets the level of a logger. One or several logger names can be specified as arguments
and the -l option specify the level among the trace, debug, info, warn and error levels. When no level is
specified, the level is cleared and the level will be inherited from its ancestors.

% logset -l trace foo
% logset foo

The logger name can be omitted and instead stream of logger can be consumed as it is a <Logger,Void> command.
The following set the level warn on all the available loggers:

% log ls | log set -l warn""")
  @Usage("configures the level of one of several loggers")
  @Command
  public PipeCommand<Logger, Object> set(
    @LoggerArg List<String> names,
    @LevelOpt Level level,
    @PluginOpt Plugin plugin) {

    //
    plugin = plugin ?: Plugin.autoDetect();
    if (plugin == null)
      throw new ScriptException("No usable plugin");

    return new PipeCommand<Logger, Object>() {
      @Override
      void open() {
        if (!isPiped()) {
          names.each() {
            def logger = LoggerFactory.getLogger(it);
            plugin.setLevel(logger, level);
          }
        }
      }
      @Override
      void provide(Logger element) {
        plugin.setLevel(element, level);
      }
    };
  }

  public Completion complete(org.crsh.cmdline.ParameterDescriptor<?> parameter, String prefix) {
    def builder = new Completion.Builder(prefix);
    if (parameter.getDeclaredType() == LoggerName.class) {
      loggers.each() {
        if (it.startsWith(prefix)) {
          builder.add(it.substring(prefix.length()), true);
        }
      }
    }
    return builder.build();
  }
}

enum Plugin {
  jdk , log4j ;

  public static Plugin autoDetect() {
    // Auto detect plugin from SLF4J
    def ilfName = LoggerFactory.ILoggerFactory.class.simpleName;
    switch (ilfName) {
      case "JDK14LoggerFactory":
        return Plugin.jdk;
        break;
      case "Log4jLoggerFactory":
        return Plugin.log4j;
        break;
      case "JBossLoggerFactory":
        // Here we see if we have log4j in the classpath and we use it
        try {
          Thread.currentThread().getContextClassLoader().loadClass("org.apache.log4j.LogManager");
          return Plugin.log4j;
        }
        catch (ClassNotFoundException nf) {
        }
    }
    return null;
  }

  public void setLevel(Logger logger, Level level) {
    switch (name()) {
      case "jdk":
      def f = Thread.currentThread().getContextClassLoader().loadClass("org.slf4j.impl.JDK14LoggerAdapter").getDeclaredField("logger");
      f.accessible = true;
        def julLogger = f.get(logger);
        julLogger.level = level.jdkObject;
      case "log4j":
        def l = Thread.currentThread().getContextClassLoader().loadClass("org.apache.log4j.Logger");
        def log4jLogger = l.getLogger(logger.name);
        log4jLogger.level = level.log4jObject;
    }
  }
}

enum Level { trace("FINEST","TRACE"), debug("FINER","DEBUG"), info("INFO","INFO"), warn("WARNING","WARNING"), error("SEVERE","ERROR") ;
  final String jdk;
  final String log4j;
  Level(String jdk, String log4j) {
    this.jdk = jdk;
    this.log4j=log4j;
  }
  Object getLog4jObject() {
    return Thread.currentThread().getContextClassLoader().loadClass("org.apache.log4j.Level")[log4j];
  }
  Object getJDKObject() {
    return java.util.logging.Level[jdk];
  }
  void log(Logger logger, String msg) {
    logger."${name()}"(msg);
  }
}

@Retention(RetentionPolicy.RUNTIME)
@Usage("the logger level")
@Man("The logger level to assign among {trace, debug, info, warn, error}")
@Option(names=["l","level"],completer=EnumCompleter)
@interface LevelOpt { }

@Retention(RetentionPolicy.RUNTIME)
@Usage("the message")
@Man("The message to log")
@Option(names=["m","message"])
@Required
@interface MsgOpt { }

@Retention(RetentionPolicy.RUNTIME)
@Usage("the logger name")
@Man("The name of the logger")
@Argument(name = "name")
@interface LoggerArg { }

@Retention(RetentionPolicy.RUNTIME)
@Usage("a regexp filter")
@Man("A regular expressions used to filter the loggers")
@Option(names=["f","filter"])
@interface FilterOpt { }

@Retention(RetentionPolicy.RUNTIME)
@Usage("the plugin implementation")
@Man("Force the plugin implementation to use")
@Option(names=["p","plugin"])
@interface PluginOpt { }
