/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.crsh.command;

import org.crsh.cmdline.ClassDescriptor;
import org.crsh.cmdline.CommandCompletion;
import org.crsh.cmdline.CommandFactory;
import org.crsh.cmdline.Delimiter;
import org.crsh.cmdline.IntrospectionException;
import org.crsh.cmdline.annotations.Man;
import org.crsh.cmdline.annotations.Option;
import org.crsh.cmdline.OptionDescriptor;
import org.crsh.cmdline.ParameterDescriptor;
import org.crsh.cmdline.annotations.Usage;
import org.crsh.cmdline.matcher.ClassMatch;
import org.crsh.cmdline.matcher.CmdCompletionException;
import org.crsh.cmdline.matcher.CmdInvocationException;
import org.crsh.cmdline.matcher.CmdSyntaxException;
import org.crsh.cmdline.matcher.CommandMatch;
import org.crsh.cmdline.matcher.Matcher;
import org.crsh.cmdline.matcher.MethodMatch;
import org.crsh.cmdline.matcher.OptionMatch;
import org.crsh.cmdline.matcher.Resolver;
import org.crsh.cmdline.spi.Completer;
import org.crsh.cmdline.spi.ValueCompletion;
import org.crsh.util.TypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public abstract class CRaSHCommand extends GroovyCommand implements ShellCommand {

  /** . */
  private final Logger log = LoggerFactory.getLogger(getClass());

  /** . */
  private final ClassDescriptor<?> descriptor;

  /** The unmatched text, only valid during an invocation. */
  protected String unmatched;

  /** . */
  @Option(names = {"h","help"})
  @Usage("command usage")
  @Man("Provides command usage")
  private boolean help;

  protected CRaSHCommand() throws IntrospectionException {
    this.descriptor = CommandFactory.create(getClass());
    this.help = false;
    this.unmatched = null;
  }

  /**
   * Returns the command descriptor.
   *
   * @return the command descriptor
   */
  public ClassDescriptor<?> getDescriptor() {
    return descriptor;
  }

  protected final String readLine(String msg) {
    return readLine(msg, true);
  }

  protected final String readLine(String msg, boolean echo) {
    if (context instanceof InvocationContext) {
      return ((InvocationContext)context).readLine(msg, echo);
    } else {
      throw new IllegalStateException("No current context of interaction with the term");
    }
  }

  public final String getUnmatched() {
    return unmatched;
  }

  public final CommandCompletion complete(CommandContext context, String line) {

    // WTF
    Matcher analyzer = descriptor.matcher("main");

    //
    Completer completer = this instanceof Completer ? (Completer)this : null;

    //
    try {
      this.context = context;

      //
      return analyzer.complete(completer, line);
    }
    catch (CmdCompletionException e) {
      log.error("Error during completion of line " + line, e);
      return new CommandCompletion(Delimiter.EMPTY, ValueCompletion.create());
    } finally {
      this.context = null;
    }
  }

  public final String describe(String line, DescriptionFormat mode) {

    // WTF
    Matcher analyzer = descriptor.matcher("main");

    //
    CommandMatch match = analyzer.match(line);

    //
    try {
      switch (mode) {
        case DESCRIBE:
          return match.getDescriptor().getUsage();
        case MAN:
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw);
          match.printMan(pw);
          return sw.toString();
        case USAGE:
          StringWriter sw2 = new StringWriter();
          PrintWriter pw2 = new PrintWriter(sw2);
          match.printUsage(pw2);
          return sw2.toString();
      }
    }
    catch (IOException e) {
      throw new AssertionError(e);
    }

    //
    return null;
  }

  static ScriptException toScript(Throwable cause) {
    if (cause instanceof ScriptException) {
      return (ScriptException)cause;
    } if (cause instanceof groovy.util.ScriptException) {
      // Special handling for groovy.util.ScriptException
      // which may be thrown by scripts because it is imported by default
      // by groovy imports
      String msg = cause.getMessage();
      ScriptException translated;
      if (msg != null) {
        translated = new ScriptException(msg);
      } else {
        translated = new ScriptException();
      }
      translated.setStackTrace(cause.getStackTrace());
      return translated;
    } else {
      return new ScriptException(cause);
    }
  }

  public CommandInvoker<?, ?> resolveInvoker(String name, Map<String, ?> options, List<?> args) {
    if (options.containsKey("h") || options.containsKey("help")) {
      throw new UnsupportedOperationException("Implement me");
    } else {

      Matcher matcher = descriptor.matcher("main");
      CommandMatch<CRaSHCommand, ?, ?> match = matcher.match(name, options, args);
      return resolveInvoker(match);
    }
  }

  public CommandInvoker<?, ?> resolveInvoker(String line) {
    Matcher analyzer = descriptor.matcher("main");
    final CommandMatch<CRaSHCommand, ?, ?> match = analyzer.match(line);
    return resolveInvoker(match);
  }

  public final void eval(String s) throws ScriptException, IOException {
    InvocationContext<?> context = peekContext();
    CommandInvoker invoker = context.resolve(s);
    invoker.open(context);
    invoker.flush();
    invoker.close();
  }

  public final CommandInvoker<?, ?> resolveInvoker(final CommandMatch<CRaSHCommand, ?, ?> match) {
    if (match instanceof MethodMatch) {

      //
      final MethodMatch<CRaSHCommand> methodMatch = (MethodMatch<CRaSHCommand>)match;

      //
      boolean help = false;
      for (OptionMatch optionMatch : methodMatch.getOwner().getOptionMatches()) {
        ParameterDescriptor<?> parameterDesc = optionMatch.getParameter();
        if (parameterDesc instanceof OptionDescriptor<?>) {
          OptionDescriptor<?> optionDesc = (OptionDescriptor<?>)parameterDesc;
          if (optionDesc.getNames().contains("h")) {
            help = true;
          }
        }
      }
      final boolean doHelp = help;

      //
      Class consumedType;
      Class producedType;
      Method m = methodMatch.getDescriptor().getMethod();
      if (PipeCommand.class.isAssignableFrom(m.getReturnType())) {
        Type ret = m.getGenericReturnType();
        consumedType = TypeResolver.resolveToClass(ret, PipeCommand.class, 0);
        producedType = TypeResolver.resolveToClass(ret, PipeCommand.class, 1);
      } else {
        consumedType = Void.class;
        producedType = Object.class;
        Class<?>[] parameterTypes = m.getParameterTypes();
        for (int i = 0;i < parameterTypes.length;i++) {
          Class<?> parameterType = parameterTypes[i];
          if (InvocationContext.class.isAssignableFrom(parameterType)) {
            Type contextGenericParameterType = m.getGenericParameterTypes()[i];
            producedType = TypeResolver.resolveToClass(contextGenericParameterType, InvocationContext.class, 0);
            break;
          }
        }
      }
      final Class _consumedType = consumedType;
      final Class _producedType = producedType;

      if (doHelp) {
        return new CommandInvoker<Object, Object>() {
          public Class<Object> getProducedType() {
            return _producedType;
          }
          public Class<Object> getConsumedType() {
            return _consumedType;
          }
          public void open(InvocationContext<Object> context) {
            try {
              match.printUsage(context.getWriter());
            }
            catch (IOException e) {
              throw new AssertionError(e);
            }
          }
          public void setPiped(boolean piped) {
          }
          public void provide(Object element) throws IOException {
          }
          public void flush() throws IOException {
          }
          public void close() {
          }
        };
      } else {
        if (consumedType == Void.class) {

          return new CommandInvoker<Object, Object>() {
            public Class<Object> getProducedType() {
              return _producedType;
            }
            public Class<Object> getConsumedType() {
              return _consumedType;
            }
            public void open(final InvocationContext<Object> context) {

              //
              pushContext(context);
              CRaSHCommand.this.unmatched = methodMatch.getRest();
              final Resolver resolver = new Resolver() {
                public <T> T resolve(Class<T> type) {
                  if (type.equals(InvocationContext.class)) {
                    return type.cast(context);
                  } else {
                    return null;
                  }
                }
              };

              //
              Object o;
              try {
                o = methodMatch.invoke(resolver, CRaSHCommand.this);
              } catch (CmdSyntaxException e) {
                throw new SyntaxException(e.getMessage());
              } catch (CmdInvocationException e) {
                throw toScript(e.getCause());
              }
              if (o != null) {
                context.getWriter().print(o);
              }
            }
            public void setPiped(boolean piped) {
            }
            public void provide(Object element) throws IOException {
              // We just drop the elements
            }
            public void flush() throws IOException {
              peekContext().flush();
            }
            public void close() {
              CRaSHCommand.this.unmatched = null;
              popContext();
            }
          };
        } else {
          return new CommandInvoker<Object, Object>() {
            PipeCommand real;
            boolean piped;
            public Class<Object> getProducedType() {
              return _producedType;
            }
            public Class<Object> getConsumedType() {
              return _consumedType;
            }
            public void open(final InvocationContext<Object> context) {

              //
              pushContext(context);
              CRaSHCommand.this.unmatched = methodMatch.getRest();
              final Resolver resolver = new Resolver() {
                public <T> T resolve(Class<T> type) {
                  if (type.equals(InvocationContext.class)) {
                    return type.cast(context);
                  } else {
                    return null;
                  }
                }
              };
              try {
                real = (PipeCommand)methodMatch.invoke(resolver, CRaSHCommand.this);
              }
              catch (CmdSyntaxException e) {
                throw new SyntaxException(e.getMessage());
              } catch (CmdInvocationException e) {
                throw toScript(e.getCause());
              }

              //
              real.setPiped(piped);
              real.open(context);
            }
            public void setPiped(boolean piped) {
              this.piped = piped;
            }
            public void close() {
              popContext();
            }
            public void provide(Object element) throws IOException {
              real.provide(element);
            }
            public void flush() throws IOException {
              peekContext().flush();
            }
          };
        }
      }
    } else if (match instanceof ClassMatch) {

      //
      final ClassMatch<?> classMatch = (ClassMatch)match;

      //
      boolean help = false;
      for (OptionMatch optionMatch : classMatch.getOptionMatches()) {
        ParameterDescriptor<?> parameterDesc = optionMatch.getParameter();
        if (parameterDesc instanceof OptionDescriptor<?>) {
          OptionDescriptor<?> optionDesc = (OptionDescriptor<?>)parameterDesc;
          if (optionDesc.getNames().contains("h")) {
            help = true;
          }
        }
      }
      final boolean doHelp = help;

      //
      return new CommandInvoker<Void, Object>() {
        InvocationContext context;
        public void open(InvocationContext<Object> context) {
          this.context = context;
          try {
            if (doHelp) {
              match.printUsage(context.getWriter());
            } else {
              classMatch.printUsage(context.getWriter());
            }
          }
          catch (IOException e) {
            throw new AssertionError(e);
          }
        }
        public void setPiped(boolean piped) {
        }
        public void close() {
          this.context = null;
        }
        public void provide(Void element) throws IOException {
        }
        public void flush() throws IOException {
          context.flush();
        }
        public Class<Object> getProducedType() {
          return Object.class;
        }
        public Class<Void> getConsumedType() {
          return Void.class;
        }
      };

    } else {
      return null;
    }
  }
}
