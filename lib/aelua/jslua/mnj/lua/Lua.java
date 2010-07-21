/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/Lua.java#3 $
 * Copyright (c) 2006 Nokia Corporation and/or its subsidiary(-ies).
 * All rights reserved.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject
 * to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package mnj.lua;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.FileReader;
import java.util.Enumeration;
import java.util.Stack;
import java.util.Vector;


/**
 * <p>
 * Encapsulates a Lua execution environment.  A lot of Jill's public API
 * manifests as public methods in this class.  A key part of the API is
 * the ability to call Lua functions from Java (ultimately, all Lua code
 * is executed in this manner).
 * </p>
 *
 * <p>
 * The Stack
 * </p>
 *
 * <p>
 * All arguments to Lua functions and all results returned by Lua
 * functions are placed onto a stack.  The stack can be indexed by an
 * integer in the same way as the PUC-Rio implementation.  A positive
 * index is an absolute index and ranges from 1 (the bottom-most
 * element) through to <var>n</var> (the top-most element),
 * where <var>n</var> is the number of elements on the stack.  Negative
 * indexes are relative indexes, -1 is the top-most element, -2 is the
 * element underneath that, and so on.  0 is not used.
 * </p>
 *
 * <p>
 * Note that in Jill the stack is used only for passing arguments and
 * returning results, unlike PUC-Rio.
 * </p>
 *
 * <p>
 * The protocol for calling a function is described in the {@link #call}
 * method.  In brief: push the function onto the stack, then push the
 * arguments to the call.
 * </p>
 *
 * <p>
 * The methods {@link #push}, {@link #pop}, {@link #value},
 * {@link #getTop}, {@link #setTop} are used to manipulate the stack.
 * </p>
 */
public final class Lua
{
  /** Version string. */
  public static final String VERSION = "Lua 5.1 (Jill 1.0.1)";

  /** Table of globals (global variables).  This actually shared across
   * all threads (with the same main thread), but kept in each Lua
   * thread as an optimisation.
   */
  private LuaTable global;
  private LuaTable registry;

  /** Reference the main Lua thread.  Itself if this is the main Lua
   * thread.
   */
  private Lua main;

  /** VM data stack.
   */
  private Slot[] stack = new Slot[0];
  /**
   * One more than the highest stack slot that has been written to
   * (ever).
   * Used by {@link #stacksetsize} to determine which stack slots
   * need nilling when growing the stack.
   */
  int stackhighwater;   // = 0;
  /**
   * Number of active elemements in the VM stack.  Should always be
   * <code><= stack.length</code>.
   */
  private int stackSize;        // = 0;
  /**
   * The base stack element for this stack frame.  If in a Lua function
   * then this is the element indexed by operand field 0; if in a Java
   * functipn then this is the element indexed by Lua.value(1).
   */
  private int base;     // = 0;

  int nCcalls;  // = 0;
  /** Instruction to resume execution at.  Index into code array. */
  private int savedpc;  // = 0;
  /**
   * Vector of CallInfo records.  Actually it's a Stack which is a
   * subclass of Vector, but it mostly the Vector methods that are used.
   */
  private Stack civ = new Stack();
  {
    civ.addElement(new CallInfo());
  }
  /** CallInfo record for currently active function. */
  private CallInfo ci()
  {
    return (CallInfo)civ.lastElement();
  }

  /** Open Upvalues.  All UpVal objects that reference the VM stack.
   * openupval is a java.util.Vector of UpVal stored in order of stack
   * slot index: higher stack indexes are stored at higher Vector
   * positions.
   */
  private Vector openupval = new Vector();

  int hookcount;
  int basehookcount;
  boolean allowhook = true;
  Hook hook;
  int hookmask;

  /** Number of list items to accumulate before a SETLIST instruction. */
  static final int LFIELDS_PER_FLUSH = 50;

  /** Limit for table tag-method chains (to avoid loops) */
  private static final int MAXTAGLOOP = 100;

  /**
   * The current error handler (set by {@link #pcall}).  A Lua
   * function to call.
   */
  private Object errfunc;

  /**
   * thread activation status.
   */
  private int status;

  /** Nonce object used by pcall and friends (to detect when an
   * exception is a Lua error). */
  private static final String LUA_ERROR = "";

  /** Metatable for primitive types.  Shared between all threads. */
  private LuaTable[] metatable;

  /**
   * Maximum number of local variables per function.  As per
   * LUAI_MAXVARS from "luaconf.h".  Default access so that {@link
   * FuncState} can see it.
   */
  static final int MAXVARS = 200;
  static final int MAXSTACK = 250;
  static final int MAXUPVALUES = 60;

  /**
   * Stored in Slot.r to denote a numeric value (which is stored at 
   * Slot.d).
   */
  static final Object NUMBER = new Object();

  /**
   * Spare Slot used for a temporary.
   */
  private static final Slot SPARE_SLOT = new Slot();

  /**
   * Registry key for loaded modules.
   */
  static final String LOADED = "_LOADED";

  /**
   * Used to construct a Lua thread that shares its global state with
   * another Lua state.
   */
  private Lua(Lua L)
  {
    // Copy the global state, that's shared across all threads that
    // share the same main thread, into the new Lua thread.
    // Any more than this and the global state should be shunted to a
    // separate object (as it is in PUC-Rio).
    this.global = L.global;
    this.registry = L.registry;
    this.metatable = L.metatable;
    this.main = L;
  }

  //////////////////////////////////////////////////////////////////////
  // Public API

  /**
   * Creates a fresh Lua state.
   */
  public Lua()
  {
    this.global = new LuaTable();
    this.registry = new LuaTable();
    this.metatable = new LuaTable[NUM_TAGS];
    this.main = this;
  }

  /**
   * Equivalent of LUA_MULTRET.
   */
  // Required, by vmPoscall, to be negative.
  public static final int MULTRET = -1;
  /**
   * The Lua <code>nil</code> value.
   */
  public static final Object NIL = new Object();

  // Lua type tags, from lua.h
  /** Lua type tag, representing no stack value. */
  public static final int TNONE         = -1;
  /** Lua type tag, representing <code>nil</code>. */
  public static final int TNIL          = 0;
  /** Lua type tag, representing boolean. */
  public static final int TBOOLEAN      = 1;
  // TLIGHTUSERDATA not available.  :todo: make available?
  /** Lua type tag, representing numbers. */
  public static final int TNUMBER       = 3;
  /** Lua type tag, representing strings. */
  public static final int TSTRING       = 4;
  /** Lua type tag, representing tables. */
  public static final int TTABLE        = 5;
  /** Lua type tag, representing functions. */
  public static final int TFUNCTION     = 6;
  /** Lua type tag, representing userdata. */
  public static final int TUSERDATA     = 7;
  /** Lua type tag, representing threads. */
  public static final int TTHREAD       = 8;
  /** Number of type tags.  Should be one more than the
   * last entry in the list of tags.
   */
  private static final int NUM_TAGS     = 9;
  /** Names for above type tags, starting from {@link #TNIL}.
   * Equivalent to luaT_typenames.
   */
  private static final String[] TYPENAME =
  {
    "nil", "boolean", "userdata", "number",
    "string", "table", "function", "userdata", "thread"
  };

  /**
   * Minimum stack size that Lua Java functions gets.  May turn out to
   * be silly / redundant.
   */
  public static final int MINSTACK = 20;

  /** Status code, returned from pcall and friends, that indicates the
   * thread has yielded.
   */
  public static final int YIELD         = 1;
  /** Status code, returned from pcall and friends, that indicates
   * a runtime error.
   */
  public static final int ERRRUN        = 2;
  /** Status code, returned from pcall and friends, that indicates
   * a syntax error.
   */
  public static final int ERRSYNTAX     = 3;
  /** Status code, returned from pcall and friends, that indicates
   * a memory allocation error.
   */
  private static final int ERRMEM        = 4;
  /** Status code, returned from pcall and friends, that indicates
   * an error whilst running the error handler function.
   */
  public static final int ERRERR        = 5;
  /** Status code, returned from loadFile and friends, that indicates
   * an IO error.
   */
  public static final int ERRFILE       = 6;

  // Enums for gc().
  /** Action, passed to {@link #gc}, that requests the GC to stop. */
  public static final int GCSTOP        = 0;
  /** Action, passed to {@link #gc}, that requests the GC to restart. */
  public static final int GCRESTART     = 1;
  /** Action, passed to {@link #gc}, that requests a full collection. */
  public static final int GCCOLLECT     = 2;
  /** Action, passed to {@link #gc}, that returns amount of memory
   * (in Kibibytes) in use (by the entire Java runtime).
   */
  public static final int GCCOUNT       = 3;
  /** Action, passed to {@link #gc}, that returns the remainder of
   * dividing the amount of memory in use by 1024.
   */
  public static final int GCCOUNTB      = 4;
  /** Action, passed to {@link #gc}, that requests an incremental
   * garbage collection be performed.
   */
  public static final int GCSTEP        = 5;
  /** Action, passed to {@link #gc}, that sets a new value for the
   * <var>pause</var> of the collector.
   */
  public static final int GCSETPAUSE    = 6;
  /** Action, passed to {@link #gc}, that sets a new values for the
   * <var>step multiplier</var> of the collector.
   */
  public static final int GCSETSTEPMUL  = 7;

  // Some of the hooks, etc, aren't implemented, so remain private.
  private static final int HOOKCALL = 0;
  private static final int HOOKRET = 1;
  private static final int HOOKLINE = 2;
  /**
   * When {@link Hook} callback is called as a line hook, its
   * <var>ar.event</var> field is <code>HOOKCOUNT</code>.
   */
  public static final int HOOKCOUNT = 3;
  private static final int HOOKTAILRET = 4;

  private static final int MASKCALL = 1 << HOOKCALL;
  private static final int MASKRET  = 1 << HOOKRET;
  private static final int MASKLINE = 1 << HOOKLINE;
  /**
   * Bitmask that specifies count hook in call to {@link #setHook}.
   */
  public static final int MASKCOUNT = 1 << HOOKCOUNT;


  /**
   * Calls a Lua value.  Normally this is called on functions, but the
   * semantics of Lua permit calls on any value as long as its metatable
   * permits it.
   *
   * In order to call a function, the function must be
   * pushed onto the stack, then its arguments must be
   * {@link #push pushed} onto the stack; the first argument is pushed
   * directly after the function,
   * then the following arguments are pushed in order (direct
   * order).  The parameter <var>nargs</var> specifies the number of
   * arguments (which may be 0).
   *
   * When the function returns the function value on the stack and all
   * the arguments are removed from the stack and replaced with the
   * results of the function, adjusted to the number specified by
   * <var>nresults</var>.  So the first result from the function call will
   * be at the same index where the function was immediately prior to
   * calling this method.
   *
   * @param nargs     The number of arguments in this function call.
   * @param nresults  The number of results required.
   */
  public void call(int nargs, int nresults)
  {
    apiChecknelems(nargs+1);
    int func = stackSize - (nargs + 1);
    this.vmCall(func, nresults);
  }

  /**
   * Closes a Lua state.  In this implementation, this method does
   * nothing.
   */
  public void close()
  {
  }

  /**
   * Concatenate values (usually strings) on the stack.
   * <var>n</var> values from the top of the stack are concatenated, as
   * strings, and replaced with the resulting string.
   * @param n  the number of values to concatenate.
   */
  public void concat(int n)
  {
    apiChecknelems(n);
    if (n >= 2)
    {
      vmConcat(n, (stackSize - base) - 1);
      pop(n-1);
    }
    else if (n == 0)          // push empty string
    {
      push("");
    } // else n == 1; nothing to do
  }

  /**
   * Creates a new empty table and returns it.
   * @param narr  number of array elements to pre-allocate.
   * @param nrec  number of non-array elements to pre-allocate.
   * @return a fresh table.
   * @see #newTable
   */
  public LuaTable createTable(int narr, int nrec)
  {
    return new LuaTable(narr, nrec);
  }

  /**
   * Dumps a function as a binary chunk.
   * @param function  the Lua function to dump.
   * @param writer    the stream that receives the dumped binary.
   * @throws IOException when writer does.
   */
  public static void dump(Object function, OutputStream writer)
      throws IOException
  {
    if (!(function instanceof LuaFunction))
    {
      throw new IOException("Cannot dump " + typeName(type(function)));
    }
    LuaFunction f = (LuaFunction)function;
    uDump(f.proto(), writer, false);
  }

  /**
   * Tests for equality according to the semantics of Lua's
   * <code>==</code> operator (so may call metamethods).
   * @param o1  a Lua value.
   * @param o2  another Lua value.
   * @return true when equal.
   */
  public boolean equal(Object o1, Object o2)
  {
    if (o1 instanceof Double)
    {
      return o1.equals(o2);
    }
    return vmEqualRef(o1, o2);
  }

  /**
   * Generates a Lua error using the error message.
   * @param message  the error message.
   * @return never.
   */
  public int error(Object message)
  {
    return gErrormsg(message);
  }

  /**
   * Control garbage collector.  Note that in Jill most of the options
   * to this function make no sense and they will not do anything.
   * @param what  specifies what GC action to take.
   * @param data  data that may be used by the action.
   * @return varies.
   */
  public int gc(int what, int data)
  {
//    Runtime rt;

    switch (what)
    {
      case GCSTOP:
        return 0;
      case GCRESTART:
      case GCCOLLECT:
      case GCSTEP:
        System.gc();
        return 0;
      case GCCOUNT:
//        rt = Runtime.getRuntime();
        return 0;//(int)((rt.totalMemory() - rt.freeMemory()) / 1024);
      case GCCOUNTB:
//        rt = Runtime.getRuntime();
        return 0;//(int)((rt.totalMemory() - rt.freeMemory()) % 1024);
      case GCSETPAUSE:
      case GCSETSTEPMUL:
        return 0;
    }
    return 0;
  }

  /**
   * Returns the environment table of the Lua value.
   * @param o  the Lua value.
   * @return its environment table.
   */
  public LuaTable getFenv(Object o)
  {
    if (o instanceof LuaFunction)
    {
      LuaFunction f = (LuaFunction)o;
      return f.getEnv();
    }
    if (o instanceof LuaJavaCallback)
    {
      LuaJavaCallback f = (LuaJavaCallback)o;
      // :todo: implement this case.
      return null;
    }

    if (o instanceof LuaUserdata)
    {
      LuaUserdata u = (LuaUserdata)o;
      return u.getEnv();
    }
    if (o instanceof Lua)
    {
      Lua l = (Lua)o;
      return l.global;
    }
    return null;
  }

  /**
   * Get a field from a table (or other object).
   * @param t      The object whose field to retrieve.
   * @param field  The name of the field.
   * @return  the Lua value
   */
  public Object getField(Object t, String field)
  {
    return getTable(t, field);
  }

  /**
   * Get a global variable.
   * @param name  The name of the global variable.
   * @return  The value of the global variable.
   */
  public Object getGlobal(String name)
  {
    return getField(global, name);
  }

  /**
   * Gets the global environment.  The global environment, where global
   * variables live, is returned as a <code>LuaTable</code>.  Note that
   * modifying this table has exactly the same effect as creating or
   * changing global variables from within Lua.
   * @return  The global environment as a table.
   */
  public LuaTable getGlobals()
  {
    return global;
  }

  /** Get metatable.
   * @param o  the Lua value whose metatable to retrieve.
   * @return The metatable, or null if there is no metatable.
   */
  public LuaTable getMetatable(Object o)
  {
    LuaTable mt;

    if (o instanceof LuaTable)
    {
      LuaTable t = (LuaTable)o;
      mt = t.getMetatable();
    }
    else if (o instanceof LuaUserdata)
    {
      LuaUserdata u = (LuaUserdata)o;
      mt = u.getMetatable();
    }
    else
    {
      mt = metatable[type(o)];
    }
    return mt;
  }

  /**
   * Gets the registry table.
   */
  public LuaTable getRegistry()
  {
    return registry;
  }

  /**
   * Indexes into a table and returns the value.
   * @param t  the Lua value to index.
   * @param k  the key whose value to return.
   * @return the value t[k].
   */
  public Object getTable(Object t, Object k)
  {
    Slot s = new Slot(k);
    Slot v = new Slot();
    vmGettable(t, s, v);
    return v.asObject();
  }

  /**
   * Gets the number of elements in the stack.  If the stack is not
   * empty then this is the index of the top-most element.
   * @return number of stack elements.
   */
  public int getTop()
  {
    return stackSize - base;
  }

  /**
   * Insert Lua value into stack immediately at specified index.  Values
   * in stack at that index and higher get pushed up.
   * @param o    the Lua value to insert into the stack.
   * @param idx  the stack index at which to insert.
   */
  public void insert(Object o, int idx)
  {
    idx = absIndexUnclamped(idx);
    stackInsertAt(o, idx);
  }

  /**
   * Tests that an object is a Lua boolean.
   * @param o  the Object to test.
   * @return true if and only if the object is a Lua boolean.
   */
  public static boolean isBoolean(Object o)
  {
    return o instanceof Boolean;
  }

  /**
   * Tests that an object is a Lua function implementated in Java (a Lua
   * Java Function).
   * @param o  the Object to test.
   * @return true if and only if the object is a Lua Java Function.
   */
  public static boolean isJavaFunction(Object o)
  {
    return o instanceof LuaJavaCallback;
  }

  /**
   * Tests that an object is a Lua function (implemented in Lua or
   * Java).
   * @param o  the Object to test.
   * @return true if and only if the object is a function.
   */
  public static boolean isFunction(Object o)
  {
    return o instanceof LuaFunction ||
        o instanceof LuaJavaCallback;
  }

  /**
   * Tests that a Lua thread is the main thread.
   * @return true if and only if is the main thread.
   */
  public boolean isMain()
  {
    return this == main;
  }

  /**
   * Tests that an object is Lua <code>nil</code>.
   * @param o  the Object to test.
   * @return true if and only if the object is Lua <code>nil</code>.
   */
  public static boolean isNil(Object o)
  {
    return NIL == o;
  }

  /**
   * Tests that an object is a Lua number or a string convertible to a
   * number.
   * @param o  the Object to test.
   * @return true if and only if the object is a number or a convertible string.
   */
  public static boolean isNumber(Object o)
  {
    SPARE_SLOT.setObject(o);
    return tonumber(SPARE_SLOT, NUMOP);
  }

  /**
   * Tests that an object is a Lua string or a number (which is always
   * convertible to a string).
   * @param o  the Object to test.
   * @return true if and only if object is a string or number.
   */
  public static boolean isString(Object o)
  {
    return o instanceof String || o instanceof Double;
  }

  /**
   * Tests that an object is a Lua table.
   * @param o  the Object to test.
   * @return <code>true</code> if and only if the object is a Lua table.
   */
  public static boolean isTable(Object o)
  {
    return o instanceof LuaTable;
  }

  /**
   * Tests that an object is a Lua thread.
   * @param o  the Object to test.
   * @return <code>true</code> if and only if the object is a Lua thread.
   */
  public static boolean isThread(Object o)
  {
    return o instanceof Lua;
  }

  /**
   * Tests that an object is a Lua userdata.
   * @param o  the Object to test.
   * @return true if and only if the object is a Lua userdata.
   */
  public static boolean isUserdata(Object o)
  {
    return o instanceof LuaUserdata;
  }

  /**
   * <p>
   * Tests that an object is a Lua value.  Returns <code>true</code> for
   * an argument that is a Jill representation of a Lua value,
   * <code>false</code> for Java references that are not Lua values.
   * For example <code>isValue(new LuaTable())</code> is
   * <code>true</code>, but <code>isValue(new Object[] { })</code> is
   * <code>false</code> because Java arrays are not a representation of
   * any Lua value.
   * </p>
   * <p>
   * PUC-Rio Lua provides no
   * counterpart for this method because in their implementation it is
   * impossible to get non Lua values on the stack, whereas in Jill it
   * is common to mix Lua values with ordinary, non Lua, Java objects.
   * </p>
   * @param o  the Object to test.
   * @return true if and if it represents a Lua value.
   */
  public static boolean isValue(Object o)
  {
    return o == NIL ||
        o instanceof Boolean ||
        o instanceof String ||
        o instanceof Double ||
        o instanceof LuaFunction ||
        o instanceof LuaJavaCallback ||
        o instanceof LuaTable ||
        o instanceof LuaUserdata;
  }

  /**
   * Compares two Lua values according to the semantics of Lua's
   * <code>&lt;</code> operator, so may call metamethods.
   * @param o1  the left-hand operand.
   * @param o2  the right-hand operand.
   * @return true when <code>o1 < o2</code>.
   */
  public boolean lessThan(Object o1, Object o2)
  {
    Slot a = new Slot(o1);
    Slot b = new Slot(o2);
    return vmLessthan(a, b);
  }

  /**
   * <p>
   * Loads a Lua chunk in binary or source form.
   * Comparable to C's lua_load.  If the chunk is determined to be
   * binary then it is loaded directly.  Otherwise the chunk is assumed
   * to be a Lua source chunk and compilation is required first; the
   * <code>InputStream</code> is used to create a <code>Reader</code>
   * using the UTF-8 encoding
   * (using a second argument of <code>"UTF-8"</code> to the
   * {@link java.io.InputStreamReader#InputStreamReader(java.io.InputStream,
   * java.lang.String)}
   * constructor) and the Lua source is compiled.
   * </p>
   * <p>
   * If successful, The compiled chunk, a Lua function, is pushed onto
   * the stack and a zero status code is returned.  Otherwise a non-zero
   * status code is returned to indicate an error and the error message
   * is pushed onto the stack.
   * </p>
   * @param in         The binary chunk as an InputStream, for example from
   *                   {@link Class#getResourceAsStream}.
   * @param chunkname  The name of the chunk.
   * @return           A status code.
   */
  public int load(InputStream in, String chunkname)
  {
    push(new LuaInternal(in, chunkname));
    return pcall(0, 1, null);
  }

  /**
   * Loads a Lua chunk in source form.
   * Comparable to C's lua_load.  This method takes a {@link
   * java.io.Reader} parameter,
   * and is normally used to load Lua chunks in source form.
   * However, it if the input looks like it is the output from Lua's
   * <code>string.dump</code> function then it will be processed as a
   * binary chunk.
   * In every other respect this method is just like {@link
   * #load(InputStream, String)}.
   * @param in         The source chunk as a Reader, for example from
   *                   <code>java.io.InputStreamReader(Class.getResourceAsStream())</code>.
   * @param chunkname  The name of the chunk.
   * @return           A status code.
   * @see java.io.InputStreamReader
   */
  public int load(Reader in, String chunkname)
  {
    push(new LuaInternal(in, chunkname));
    return pcall(0, 1, null);
  }

  /**
   * Slowly get the next key from a table.  Unlike most other functions
   * in the API this one uses the stack.  The top-of-stack is popped and
   * used to find the next key in the table at the position specified by
   * index.  If there is a next key then the key and its value are
   * pushed onto the stack and <code>true</code> is returned.
   * Otherwise (the end of the table has been reached)
   * <code>false</code> is returned.
   * @param idx  stack index of table.
   * @return  true if and only if there are more keys in the table.
   * @deprecated Use {@link #tableKeys} enumeration protocol instead.
   */
  public boolean next(int idx)
  {
    Object o = value(idx);
    // :todo: api check
    LuaTable t = (LuaTable)o;
    Object key = value(-1);
    pop(1);
    Enumeration e = t.keys();
    if (key == NIL)
    {
      if (e.hasMoreElements())
      {
        key = e.nextElement();
        push(key);
        push(t.getlua(key));
        return true;
      }
      return false;
    }
    while (e.hasMoreElements())
    {
      Object k = e.nextElement();
      if (k.equals(key))
      {
        if (e.hasMoreElements())
        {
          key = e.nextElement();
          push(key);
          push(t.getlua(key));
          return true;
        }
        return false;
      }
    }
    // protocol error which we could potentially diagnose.
    return false;
  }

  /**
   * Creates a new empty table and returns it.
   * @return a fresh table.
   * @see #createTable
   */
  public LuaTable newTable()
  {
    return new LuaTable();
  }

  /**
   * Creates a new Lua thread and returns it.
   * @return a new Lua thread.
   */
  public Lua newThread()
  {
    return new Lua(this);
  }

  /**
   * Wraps an arbitrary Java reference in a Lua userdata and returns it.
   * @param ref  the Java reference to wrap.
   * @return the new LuaUserdata.
   */
  public LuaUserdata newUserdata(Object ref)
  {
    return new LuaUserdata(ref);
  }

  /**
   * Return the <em>length</em> of a Lua value.  For strings this is
   * the string length; for tables, this is result of the <code>#</code>
   * operator; for other values it is 0.
   * @param o  a Lua value.
   * @return its length.
   */
  public static int objLen(Object o)
  {
    if (o instanceof String)
    {
      String s = (String)o;
      return s.length();
    }
    if (o instanceof LuaTable)
    {
      LuaTable t = (LuaTable)o;
      return t.getn();
    }
    if (o instanceof Double)
    {
      return vmTostring(o).length();
    }
    return 0;
  }


  /**
   * <p>
   * Protected {@link #call}.  <var>nargs</var> and
   * <var>nresults</var> have the same meaning as in {@link #call}.
   * If there are no errors during the call, this method behaves as
   * {@link #call}.  Any errors are caught, the error object (usually
   * a message) is pushed onto the stack, and a non-zero error code is
   * returned.
   * </p>
   * <p>
   * If <var>er</var> is <code>null</code> then the error object that is
   * on the stack is the original error object.  Otherwise
   * <var>ef</var> specifies an <em>error handling function</em> which
   * is called when the original error is generated; its return value
   * becomes the error object left on the stack by <code>pcall</code>.
   * </p>
   * @param nargs     number of arguments.
   * @param nresults  number of result required.
   * @param ef        error function to call in case of error.
   * @return 0 if successful, else a non-zero error code.
   */
  public int pcall(int nargs, int nresults, Object ef)
  {
    apiChecknelems(nargs+1);
    int restoreStack = stackSize - (nargs + 1);
    // Most of this code comes from luaD_pcall
    int restoreCi = civ.size();
    int oldnCcalls = nCcalls;
    Object old_errfunc = errfunc;
    errfunc = ef;
    boolean old_allowhook = allowhook;
    int errorStatus = 0;
    try
    {
      call(nargs, nresults);
    }
    catch (LuaError e)
    {
      fClose(restoreStack);   // close eventual pending closures
      dSeterrorobj(e.errorStatus, restoreStack);
      nCcalls = oldnCcalls;
      civ.setSize(restoreCi);
      CallInfo ci = ci();
      base = ci.base();
      savedpc = ci.savedpc();
      allowhook = old_allowhook;
      errorStatus = e.errorStatus;
    }
/* gwt does not throw this?
     catch (OutOfMemoryError e)
    {
      fClose(restoreStack);     // close eventual pending closures
      dSeterrorobj(ERRMEM, restoreStack);
      nCcalls = oldnCcalls;
      civ.setSize(restoreCi);
      CallInfo ci = ci();
      base = ci.base();
      savedpc = ci.savedpc();
      allowhook = old_allowhook;
      errorStatus = ERRMEM;
    }
*/
    errfunc = old_errfunc;
    return errorStatus;
  }

  /**
   * Removes (and discards) the top-most <var>n</var> elements from the stack.
   * @param n  the number of elements to remove.
   */
  public void pop(int n)
  {
    if (n < 0)
    {
      throw new IllegalArgumentException();
    }
    stacksetsize(stackSize - n);
  }

  /**
   * Pushes a value onto the stack in preparation for calling a
   * function (or returning from one).  See {@link #call} for
   * the protocol to be used for calling functions.  See {@link
   * #pushNumber} for pushing numbers, and {@link #pushValue} for
   * pushing a value that is already on the stack.
   * @param o  the Lua value to push.
   */
  public void push(Object o)
  {
    // see also a private overloaded version of this for Slot.
    stackAdd(o);
  }

  /**
   * Push boolean onto the stack.
   * @param b  the boolean to push.
   */
  public void pushBoolean(boolean b)
  {
    push(valueOfBoolean(b));
  }

  /**
   * Push literal string onto the stack.
   * @param s  the string to push.
   */
  public void pushLiteral(String s)
  {
    push(s);
  }

  /** Push nil onto the stack. */
  public void pushNil()
  {
    push(NIL);
  }

  /**
   * Pushes a number onto the stack.  See also {@link #push}.
   * @param d  the number to push.
   */
  public void pushNumber(double d)
  {
    // :todo: optimise to avoid creating Double instance
    push(new Double(d));
  }

  /**
   * Push string onto the stack.
   * @param s  the string to push.
   */
  public void pushString(String s)
  {
    push(s);
  }

  /**
   * Copies a stack element onto the top of the stack.
   * Equivalent to <code>L.push(L.value(idx))</code>.
   * @param idx  stack index of value to push.
   */
  public void pushValue(int idx)
  {
    // :todo: optimised to avoid creating Double instance
    push(value(idx));
  }

  /**
   * Implements equality without metamethods.
   * @param o1  the first Lua value to compare.
   * @param o2  the other Lua value.
   * @return  true if and only if they compare equal.
   */
  public static boolean rawEqual(Object o1, Object o2)
  {
    return oRawequal(o1, o2);
  }

  /**
   * Gets an element from a table, without using metamethods.
   * @param t  The table to access.
   * @param k  The index (key) into the table.
   * @return The value at the specified index.
   */
  public static Object rawGet(Object t, Object k)
  {
    LuaTable table = (LuaTable)t;
    return table.getlua(k);
  }

  /**
   * Gets an element from an array, without using metamethods.
   * @param t  the array (table).
   * @param i  the index of the element to retrieve.
   * @return  the value at the specified index.
   */
  public static Object rawGetI(Object t, int i)
  {
    LuaTable table = (LuaTable)t;
    return table.getnum(i);
  }

  /**
   * Sets an element in a table, without using metamethods.
   * @param t  The table to modify.
   * @param k  The index into the table.
   * @param v  The new value to be stored at index <var>k</var>.
   */
  public void rawSet(Object t, Object k, Object v)
  {
    LuaTable table = (LuaTable)t;
    table.putlua(this, k, v);
  }

  /**
   * Sets an element in an array, without using metamethods.
   * @param t  the array (table).
   * @param i  the index of the element to set.
   * @param v  the new value to be stored at index <var>i</var>.
   */
  public void rawSetI(Object t, int i, Object v)
  {
    apiCheck(t instanceof LuaTable);
    LuaTable h = (LuaTable)t;
    h.putnum(i, v);
  }

  /**
   * Register a {@link LuaJavaCallback} as the new value of the global
   * <var>name</var>.
   * @param name  the name of the global.
   * @param f     the LuaJavaCallback to register.
   */
  public void register(String name, LuaJavaCallback f)
  {
    setGlobal(name, f);
  }

  /**
   * Starts and resumes a Lua thread.  Threads can be created using
   * {@link #newThread}.  Once a thread has begun executing it will
   * run until it either completes (with error or normally) or has been
   * suspended by invoking {@link #yield}.
   * @param narg  Number of values to pass to thread.
   * @return Lua.YIELD, 0, or an error code.
   */
  public int resume(int narg)
  {
    if (status != YIELD)
    {
      if (status != 0)
        return resume_error("cannot resume dead coroutine");
      else if (civ.size() != 1)
        return resume_error("cannot resume non-suspended coroutine");
    }
    // assert errfunc == 0 && nCcalls == 0;
    int errorStatus = 0;
protect:
    try
    {
      // This block is equivalent to resume from ldo.c
      int firstArg = stackSize - narg;
      if (status == 0)  // start coroutine?
      {
        // assert civ.size() == 1 && firstArg > base);
        if (vmPrecall(firstArg - 1, MULTRET) != PCRLUA)
          break protect;
      }
      else      // resuming from previous yield
      {
        // assert status == YIELD;
        status = 0;
        if (!isLua(ci()))       // 'common' yield
        {
          // finish interrupted execution of 'OP_CALL'
          // assert ...
          if (vmPoscall(firstArg))      // complete it...
            stacksetsize(ci().top());  // and correct top
        }
        else    // yielded inside a hook: just continue its execution
          base = ci().base();
      }
      vmExecute(civ.size() - 1);
    }
    catch (LuaError e)
    {
      status = e.errorStatus;   // mark thread as 'dead'
      dSeterrorobj(e.errorStatus, stackSize);
      ci().setTop(stackSize);
    }
    return status;
  }

  /**
   * Set the environment for a function, thread, or userdata.
   * @param o      Object whose environment will be set.
   * @param table  Environment table to use.
   * @return true if the object had its environment set, false otherwise.
   */
  public boolean setFenv(Object o, Object table)
  {
    // :todo: consider implementing common env interface for
    // LuaFunction, LuaJavaCallback, LuaUserdata, Lua.  One cast to an
    // interface and an interface method call may be shorter
    // than this mess.
    LuaTable t = (LuaTable)table;

    if (o instanceof LuaFunction)
    {
      LuaFunction f = (LuaFunction)o;
      f.setEnv(t);
      return true;
    }
    if (o instanceof LuaJavaCallback)
    {
      LuaJavaCallback f = (LuaJavaCallback)o;
      // :todo: implement this case.
      return false;
    }
    if (o instanceof LuaUserdata)
    {
      LuaUserdata u = (LuaUserdata)o;
      u.setEnv(t);
      return true;
    }
    if (o instanceof Lua)
    {
      Lua l = (Lua)o;
      l.global = t;
      return true;
    }
    return false;
  }

  /**
   * Set a field in a Lua value.
   * @param t     Lua value of which to set a field.
   * @param name  Name of field to set.
   * @param v     new Lua value for field.
   */
  public void setField(Object t, String name, Object v)
  {
    Slot s = new Slot(name);
    vmSettable(t, s, v);
  }

  /**
   * Sets the metatable for a Lua value.
   * @param o   Lua value of which to set metatable.
   * @param mt  The new metatable.
   */
  public void setMetatable(Object o, Object mt)
  {
    if (isNil(mt))
    {
      mt = null;
    }
    else
    {
      apiCheck(mt instanceof LuaTable);
    }
    LuaTable mtt = (LuaTable)mt;
    if (o instanceof LuaTable)
    {
      LuaTable t = (LuaTable)o;
      t.setMetatable(mtt);
    }
    else if (o instanceof LuaUserdata)
    {
      LuaUserdata u = (LuaUserdata)o;
      u.setMetatable(mtt);
    }
    else
    {
      metatable[type(o)] = mtt;
    }
  }

  /**
   * Set a global variable.
   * @param name   name of the global variable to set.
   * @param value  desired new value for the variable.
   */
  public void setGlobal(String name, Object value)
  {
    Slot s = new Slot(name);
    vmSettable(global, s, value);
  }

  /**
   * Does the equivalent of <code>t[k] = v</code>.
   * @param t  the table to modify.
   * @param k  the index to modify.
   * @param v  the new value at index <var>k</var>.
   */
  public void setTable(Object t, Object k, Object v)
  {
    Slot s = new Slot(k);
    vmSettable(t, s, v);
  }

  /**
   * Set the stack top.
   * @param n  the desired size of the stack (in elements).
   */
  public void setTop(int n)
  {
    if (n < 0)
    {
      throw new IllegalArgumentException();
    }
    stacksetsize(base+n);
  }

  /**
   * Status of a Lua thread.
   * @return 0, an error code, or Lua.YIELD.
   */
  public int status()
  {
    return status;
  }

  /**
   * Returns an {@link java.util.Enumeration} for the keys of a table.
   * @param t  a Lua table.
   * @return an Enumeration object.
   */
  public Enumeration tableKeys(Object t)
  {
    if (!(t instanceof LuaTable))
    {
      error("table required");
      // NOTREACHED
    }
    return ((LuaTable)t).keys();
  }

  /**
   * Convert to boolean.
   * @param o  Lua value to convert.
   * @return  the resulting primitive boolean.
   */
  public boolean toBoolean(Object o)
  {
    return !(o == NIL || Boolean.FALSE.equals(o));
  }

  /**
   * Convert to integer and return it.  Returns 0 if cannot be
   * converted.
   * @param o  Lua value to convert.
   * @return  the resulting int.
   */
  public int toInteger(Object o)
  {
    return (int)toNumber(o);
  }

  /**
   * Convert to number and return it.  Returns 0 if cannot be
   * converted.
   * @param o  Lua value to convert.
   * @return  The resulting number.
   */
  public double toNumber(Object o)
  {
    SPARE_SLOT.setObject(o);
    if (tonumber(SPARE_SLOT, NUMOP))
    {
      return NUMOP[0];
    }
    return 0;
  }

  /**
   * Convert to string and return it.  If value cannot be converted then
   * <code>null</code> is returned.  Note that unlike
   * <code>lua_tostring</code> this
   * does not modify the Lua value.
   * @param o  Lua value to convert.
   * @return  The resulting string.
   */
  public String toString(Object o)
  {
    return vmTostring(o);
  }

  /**
   * Convert to Lua thread and return it or <code>null</code>.
   * @param o  Lua value to convert.
   * @return  The resulting Lua instance.
   */
  public Lua toThread(Object o)
  {
    if (!(o instanceof Lua))
    {
      return null;
    }
    return (Lua)o;
  }

  /**
   * Convert to userdata or <code>null</code>.  If value is a {@link
   * LuaUserdata} then it is returned, otherwise, <code>null</code> is
   * returned.
   * @param o  Lua value.
   * @return  value as userdata or <code>null</code>.
   */
  public LuaUserdata toUserdata(Object o)
  {
    if (o instanceof LuaUserdata)
    {
      return (LuaUserdata)o;
    }
    return null;
  }

  /**
   * Type of the Lua value at the specified stack index.
   * @param idx  stack index to type.
   * @return  the type, or {@link #TNONE} if there is no value at <var>idx</var>
   */
  public int type(int idx)
  {
    idx = absIndex(idx);
    if (idx < 0)
    {
      return TNONE;
    }
    return type(stack[idx]);
  }

  private int type(Slot s)
  {
    if (s.r == NUMBER)
    {
      return TNUMBER;
    }
    return type(s.r);
  }

  /**
   * Type of a Lua value.
   * @param o  the Lua value whose type to return.
   * @return  the Lua type from an enumeration.
   */
  public static int type(Object o)
  {
    if (o == NIL)
    {
      return TNIL;
    }
    else if (o instanceof Double)
    {
      return TNUMBER;
    }
    else if (o instanceof Boolean)
    {
      return TBOOLEAN;
    }
    else if (o instanceof String)
    {
      return TSTRING;
    }
    else if (o instanceof LuaTable)
    {
      return TTABLE;
    }
    else if (o instanceof LuaFunction || o instanceof LuaJavaCallback)
    {
      return TFUNCTION;
    }
    else if (o instanceof LuaUserdata)
    {
      return TUSERDATA;
    }
    else if (o instanceof Lua)
    {
      return TTHREAD;
    }
    return TNONE;
  }

  /**
   * Name of type.
   * @param type  a Lua type from, for example, {@link #type}.
   * @return  the type's name.
   */
  public static String typeName(int type)
  {
    if (TNONE == type)
    {
      return "no value";
    }
    return TYPENAME[type];
  }

  /**
   * Gets a value from the stack.
   * If <var>idx</var> is positive and exceeds
   * the size of the stack, {@link #NIL} is returned.
   * @param idx  the stack index of the value to retrieve.
   * @return  the Lua value from the stack.
   */
  public Object value(int idx)
  {
    idx = absIndex(idx);
    if (idx < 0)
    {
      return NIL;
    }
    return stack[idx].asObject();
  }

  /**
   * Converts primitive boolean into a Lua value.
   * @param b  the boolean to convert.
   * @return  the resulting Lua value.
   */
  public static Object valueOfBoolean(boolean b)
  {
     // If CLDC 1.1 had
     // <code>java.lang.Boolean.valueOf(boolean);</code> then I probably
     // wouldn't have written this.  This does have a small advantage:
     // code that uses this method does not need to assume that Lua booleans in
     // Jill are represented using Java.lang.Boolean.
    if (b)
    {
      return Boolean.TRUE;
    }
    else
    {
      return Boolean.FALSE;
    }
  }

  /**
   * Converts primitive number into a Lua value.
   * @param d  the number to convert.
   * @return  the resulting Lua value.
   */
  public static Object valueOfNumber(double d)
  {
    // :todo: consider interning "common" numbers, like 0, 1, -1, etc.
    return new Double(d);
  }

  /**
   * Exchange values between different threads.
   * @param to  destination Lua thread.
   * @param n   numbers of stack items to move.
   */
  public void xmove(Lua to, int n)
  {
    if (this == to)
    {
      return;
    }
    apiChecknelems(n);
    // L.apiCheck(from.G() == to.G());
    for (int i = 0; i < n; ++i)
    {
      to.push(value(-n+i));
    }
    pop(n);
  }

  /**
   * Yields a thread.  Should only be called as the return expression
   * of a Lua Java function: <code>return L.yield(nresults);</code>.
   * A {@link RuntimeException} can also be thrown to yield.  If the
   * Java code that is executing throws an instance of {@link
   * RuntimeException} (direct or indirect) then this causes the Lua 
   * thread to be suspended, as if <code>L.yield(0);</code> had been
   * executed, and the exception is re-thrown to the code that invoked
   * {@link #resume}.
   * @param nresults  Number of results to return to {@link #resume}.
   * @return  a secret value.
   */
  public int yield(int nresults)
  {
    if (nCcalls > 0)
      gRunerror("attempt to yield across metamethod/Java-call boundary");
    base = stackSize - nresults;     // protect stack slots below
    status = YIELD;
    return -1;
  }

  // Miscellaneous private functions.

  /** Convert from Java API stack index to absolute index.
   * @return an index into <code>this.stack</code> or -1 if out of range.
   */
  private int absIndex(int idx)
  {
    int s = stackSize;

    if (idx == 0)
    {
      return -1;
    }
    if (idx > 0)
    {
      if (idx + base > s)
      {
        return -1;
      }
      return base + idx - 1;
    }
    // idx < 0
    if (s + idx < base)
    {
      return -1;
    }
    return s + idx;
  }

  /**
   * As {@link #absIndex} but does not return -1 for out of range
   * indexes.  Essential for {@link #insert} because an index equal
   * to the size of the stack is valid for that call.
   */
  private int absIndexUnclamped(int idx)
  {
    if (idx == 0)
    {
      return -1;
    }
    if (idx > 0)
    {
      return base + idx - 1;
    }
    // idx < 0
    return stackSize + idx;
  }


  //////////////////////////////////////////////////////////////////////
  // Auxiliary API

  // :todo: consider placing in separate class (or macroised) so that we
  // can change its definition (to remove the check for example).
  private void apiCheck(boolean cond)
  {
    if (!cond)
    {
      throw new IllegalArgumentException();
    }
  }

  private void apiChecknelems(int n)
  {
    apiCheck(n <= stackSize - base);
  }

  /**
   * Checks a general condition and raises error if false.
   * @param cond      the (evaluated) condition to check.
   * @param numarg    argument index.
   * @param extramsg  extra error message to append.
   */
  public void argCheck(boolean cond, int numarg, String extramsg)
  {
    if (cond)
    {
      return;
    }
    argError(numarg, extramsg);
  }

  /**
   * Raise a general error for an argument.
   * @param narg      argument index.
   * @param extramsg  extra message string to append.
   * @return never (used idiomatically in <code>return argError(...)</code>)
   */
  public int argError(int narg, String extramsg)
  {
    // :todo: use debug API as per PUC-Rio
    if (true)
    {
      return error("bad argument " + narg + " (" + extramsg + ")");
    }
    return 0;
  }

  /**
   * Calls a metamethod.  Pushes 1 result onto stack if method called.
   * @param obj    stack index of object whose metamethod to call
   * @param event  metamethod (event) name.
   * @return  true if and only if metamethod was found and called.
   */
  public boolean callMeta(int obj, String event)
  {
    Object o = value(obj);
    Object ev = getMetafield(o, event);
    if (ev == NIL)
    {
      return false;
    }
    push(ev);
    push(o);
    call(1, 1);
    return true;
  }

  /**
   * Checks that an argument is present (can be anything).
   * Raises error if not.
   * @param narg  argument index.
   */
  public void checkAny(int narg)
  {
    if (type(narg) == TNONE)
    {
      argError(narg, "value expected");
    }
  }

  /**
   * Checks is a number and returns it as an integer.  Raises error if
   * not a number.
   * @param narg  argument index.
   * @return  the argument as an int.
   */
  public int checkInt(int narg)
  {
    return (int)checkNumber(narg);
  }

  /**
   * Checks is a number.  Raises error if not a number.
   * @param narg  argument index.
   * @return  the argument as a double.
   */
  public double checkNumber(int narg)
  {
    Object o = value(narg);
    double d = toNumber(o);
    if (d == 0 && !isNumber(o))
    {
      tagError(narg, TNUMBER);
    }
    return d;
  }

  /**
   * Checks that an optional string argument is an element from a set of
   * strings.  Raises error if not.
   * @param narg  argument index.
   * @param def   default string to use if argument not present.
   * @param lst   the set of strings to match against.
   * @return an index into <var>lst</var> specifying the matching string.
   */
  public int checkOption(int narg, String def, String[] lst)
  {
    String name;

    if (def == null)
    {
      name = checkString(narg);
    }
    else
    {
      name = optString(narg, def);
    }
    for (int i=0; i<lst.length; ++i)
    {
      if (lst[i].equals(name))
      {
        return i;
      }
    }
    return argError(narg, "invalid option '" + name + "'");
  }

  /**
   * Checks argument is a string and returns it.  Raises error if not a
   * string.
   * @param narg  argument index.
   * @return  the argument as a string.
   */
  public String checkString(int narg)
  {
    String s = toString(value(narg));
    if (s == null)
    {
      tagError(narg, TSTRING);
    }
    return s;
  }

  /**
   * Checks the type of an argument, raises error if not matching.
   * @param narg  argument index.
   * @param t     typecode (from {@link #type} for example).
   */
  public void checkType(int narg, int t)
  {
    if (type(narg) != t)
    {
      tagError(narg, t);
    }
  }

  /**
   * Loads and runs the given string.
   * @param s  the string to run.
   * @return  a status code, as per {@link #load}.
   */
  public int doString(String s)
  {
    int status = load(Lua.stringReader(s), s);
    if (status == 0)
    {
      status = pcall(0, MULTRET, null);
    }
    return status;
  }

  private int errfile(String what, String fname, Exception e)
  {
    push("cannot " + what + " " + fname + ": " + e.toString());
    return ERRFILE;
  }

  /**
   * Equivalent to luaL_findtable.  Instead of the table being passed on
   * the stack, it is passed as the argument <var>t</var>.
   * Likes its PUC-Rio equivalent however, this method leaves a table on
   * the Lua stack.
   */
  public String findTable(LuaTable t, String fname, int szhint)
  {
    int e = 0;
    int i = 0;
    do
    {
      e = fname.indexOf('.', i);
      String part;
      if (e < 0)
      {
        part = fname.substring(i);
      }
      else
      {
        part = fname.substring(i, e);
      }
      Object v = rawGet(t, part);
      if (isNil(v))     // no such field?
      {
        v = createTable(0,
            (e >= 0) ? 1 : szhint);     // new table for field
        setTable(t, part, v);
      }
      else if (!isTable(v))     // field has a non-table value?
      {
        return part;
      }
      t = (LuaTable)v;
      i = e + 1;
    } while (e >= 0);
    push(t);
    return null;
  }

  /**
   * Get a field (event) from an Lua value's metatable.  Returns Lua
   * <code>nil</code> if there is either no metatable or no field.
   * @param o           Lua value to get metafield for.
   * @param event       name of metafield (event).
   * @return            the field from the metatable, or nil.
   */
  public Object getMetafield(Object o, String event)
  {
    LuaTable mt = getMetatable(o);
    if (mt == null)
    {
      return NIL;
    }
    return mt.getlua(event);
  }

  boolean isNoneOrNil(int narg)
  {
    return type(narg) <= TNIL;
  }

  /**
   * Loads a Lua chunk from a file.  The <var>filename</var> argument is
   * used in a call to {@link Class#getResourceAsStream} where
   * <code>this</code> is the {@link Lua} instance, thus relative
   * pathnames will be relative to the location of the
   * <code>Lua.class</code> file.  Pushes compiled chunk, or error
   * message, onto stack.
   * @param filename  location of file.
   * @return status code, as per {@link #load}.
   */
  public int loadFile(String filename)
  {
    if (filename == null)
    {
      throw new NullPointerException();
    }
/*
	
    InputStream in = getClass().getResourceAsStream(filename);
    if (in == null)
    {
      return errfile("open", filename, new IOException());
    }
*/
    int status = 0;
    try
    {
/*
      in.mark(1);
      int c = in.read();
      if (c == '#')       // Unix exec. file?
      {
        // :todo: handle this case
      }
      in.reset();
      status = load(in, "@" + filename);
*/
      status = load(new FileReader(filename), "@" + filename);
    }
    catch (IOException e)
    {
      return errfile("read", filename, e);
    }
    return status;
  }

  /**
   * Loads a Lua chunk from a string.  Pushes compiled chunk, or error
   * message, onto stack.
   * @param s           the string to load.
   * @param chunkname   the name of the chunk.
   * @return status code, as per {@link #load}.
   */
  public int loadString(String s, String chunkname)
  {
    return load(stringReader(s), chunkname);
  }

  /**
   * Get optional integer argument.  Raises error if non-number
   * supplied.
   * @param narg  argument index.
   * @param def   default value for integer.
   * @return an int.
   */
  public int optInt(int narg, int def)
  {
    if (isNoneOrNil(narg))
    {
      return def;
    }
    return checkInt(narg);
  }

  /**
   * Get optional number argument.  Raises error if non-number supplied.
   * @param narg  argument index.
   * @param def   default value for number.
   * @return a double.
   */
  public double optNumber(int narg, double def)
  {
    if (isNoneOrNil(narg))
    {
      return def;
    }
    return checkNumber(narg);
  }

  /**
   * Get optional string argument.  Raises error if non-string supplied.
   * @param narg  argument index.
   * @param def   default value for string.
   * @return a string.
   */
  public String optString(int narg, String def)
  {
    if (isNoneOrNil(narg))
    {
      return def;
    }
    return checkString(narg);
  }

  /**
   * Creates a table in the global namespace and registers it as a loaded
   * module.
   * @return the new table
   */
  public LuaTable register(String name)
  {
    findTable(getRegistry(), LOADED, 1);
    Object loaded = value(-1);
    pop(1);
    Object t = getField(loaded, name);
    if (!isTable(t))    // not found?
    {
      // try global variable (and create one if it does not exist)
      if (findTable(getGlobals(), name, 0) != null)
      {
        error("name conflict for module '" + name + "'");
      }
      t = value(-1);
      pop(1);
      setField(loaded, name, t);        // _LOADED[name] = new table
    }
    return (LuaTable)t;
  }

  private void tagError(int narg, int tag)
  {
    typerror(narg, typeName(tag));
  }

  /**
   * Name of type of value at <var>idx</var>.
   * @param idx  stack index.
   * @return  the name of the value's type.
   */
  public String typeNameOfIndex(int idx)
  {
    return TYPENAME[type(idx)];
  }

  /**
   * Declare type error in argument.
   * @param narg   Index of argument.
   * @param tname  Name of type expected.
   */
  public void typerror(int narg, String tname)
  {
    argError(narg, tname + " expected, got " + typeNameOfIndex(narg));
  }

  /**
   * Return string identifying current position of the control at level
   * <var>level</var>.
   * @param level  specifies the call-stack level.
   * @return a description for that level.
   */
  public String where(int level)
  {
    Debug ar = getStack(level);         // check function at level
    if (ar != null)
    {
      getInfo("Sl", ar);                // get info about it
      if (ar.currentline() > 0)         // is there info?
      {
        return ar.shortsrc() + ":" + ar.currentline() + ": ";
      }
    }
    return "";  // else, no information available...
  }

  /**
   * Provide {@link java.io.Reader} interface over a <code>String</code>.
   * Equivalent of {@link java.io.StringReader#StringReader} from J2SE.
   * The ability to convert a <code>String</code> to a
   * <code>Reader</code> is required internally,
   * to provide the Lua function <code>loadstring</code>; exposed
   * externally as a convenience.
   * @param s  the string from which to read.
   * @return a {@link java.io.Reader} that reads successive chars from <var>s</var>.
   */
  public static Reader stringReader(String s)
  {
    return new StringReader(s);
  }

  //////////////////////////////////////////////////////////////////////
  // Debug

  // Methods equivalent to debug API.  In PUC-Rio most of these are in
  // ldebug.c

  boolean getInfo(String what, Debug ar)
  {
    Object f = null;
    CallInfo callinfo = null;
    // :todo: complete me
    if (ar.ici() > 0)   // no tail call?
    {
      callinfo = (CallInfo)civ.elementAt(ar.ici());
      f = stack[callinfo.function()].r;
      //# assert isFunction(f)
    }
    boolean status = auxgetinfo(what, ar, f, callinfo);
    if (what.indexOf('f') >= 0)
    {
      if (f == null)
      {
        push(NIL);
      }
      else
      {
        push(f);
      }
    }
    return status;
  }

  /**
   * Locates function activation at specified call level and returns a
   * {@link Debug}
   * record for it, or <code>null</code> if level is too high.
   * May become public.
   * @param level  the call level.
   * @return a {@link Debug} instance describing the activation record.
   */
  Debug getStack(int level)
  {
    int ici;    // Index of CallInfo

    for (ici=civ.size()-1; level > 0 && ici > 0; --ici)
    {
      CallInfo ci = (CallInfo)civ.elementAt(ici);
      --level;
      if (isLua(ci))                    // Lua function?
      {
        level -= ci.tailcalls();        // skip lost tail calls
      }
    }
    if (level == 0 && ici > 0)          // level found?
    {
      return new Debug(ici);
    }
    else if (level < 0)       // level is of a lost tail call?
    {
      return new Debug(0);
    }
    return null;
  }

  /**
   * Sets the debug hook.
   */
  public void setHook(Hook func, int mask, int count)
  {
    if (func == null || mask == 0)      // turn off hooks?
    {
      mask = 0;
      func = null;
    }
    hook = func;
    basehookcount = count;
    resethookcount();
    hookmask = mask;
  }

  /**
   * @return true is okay, false otherwise (for example, error).
   */
  private boolean auxgetinfo(String what, Debug ar, Object f, CallInfo ci)
  {
    boolean status = true;
    if (f == null)
    {
      // :todo: implement me
      return status;
    }
    for (int i=0; i<what.length(); ++i)
    {
      switch (what.charAt(i))
      {
        case 'S':
          funcinfo(ar, f);
          break;
        case 'l':
          ar.setCurrentline((ci != null) ? currentline(ci) : -1);
          break;
        case 'f':       // handled by getInfo
          break;
        // :todo: more cases.
        default:
          status = false;
      }
    }
    return status;
  }

  private int currentline(CallInfo ci)
  {
    int pc = currentpc(ci);
    if (pc < 0)
    {
      return -1;        // only active Lua functions have current-line info
    }
    else
    {
      Object faso = stack[ci.function()].r;
      LuaFunction f = (LuaFunction)faso;
      return f.proto().getline(pc);
    }
  }

  private int currentpc(CallInfo ci)
  {
    if (!isLua(ci))     // function is not a Lua function?
    {
      return -1;
    }
    if (ci == ci())
    {
      ci.setSavedpc(savedpc);
    }
    return pcRel(ci.savedpc());
  }

  private void funcinfo(Debug ar, Object cl)
  {
    if (cl instanceof LuaJavaCallback)
    {
      ar.setSource("=[Java]");
      ar.setLinedefined(-1);
      ar.setLastlinedefined(-1);
      ar.setWhat("Java");
    }
    else
    {
      Proto p = ((LuaFunction)cl).proto();
      ar.setSource(p.source());
      ar.setLinedefined(p.linedefined());
      ar.setLastlinedefined(p.lastlinedefined());
      ar.setWhat(ar.linedefined() == 0 ? "main" : "Lua");
    }
  }

  /** Equivalent to macro isLua _and_ f_isLua from lstate.h. */
  private boolean isLua(CallInfo callinfo)
  {
    Object f = stack[callinfo.function()].r;
    return f instanceof LuaFunction;
  }

  private static int pcRel(int pc)
  {
    return pc - 1;
  }

  //////////////////////////////////////////////////////////////////////
  // Do

  // Methods equivalent to the file ldo.c.  Prefixed with d.
  // Some of these are in vm* instead.

  /**
   * Equivalent to luaD_callhook.
   */
  private void dCallhook(int event, int line)
  {
    Hook hook = this.hook;
    if (hook != null && allowhook)
    {
      int top = stackSize;
      int ci_top = ci().top();
      int ici = civ.size() - 1;
      if (event == HOOKTAILRET) // not supported yet
      {
        ici = 0;
      }
      Debug ar = new Debug(ici);
      ar.setEvent(event);
      ar.setCurrentline(line);
      ci().setTop(stackSize);
      allowhook = false;        // cannot call hooks inside a hook
      hook.luaHook(this, ar);
      //# assert !allowhook
      allowhook = true;
      ci().setTop(ci_top);
      stacksetsize(top);
    }
  }

  private static final String MEMERRMSG = "not enough memory";

  /** Equivalent to luaD_seterrorobj.  It is valid for oldtop to be
   * equal to the current stack size (<code>stackSize</code>).
   * {@link #resume} uses this value for oldtop.
   */
  private void dSeterrorobj(int errcode, int oldtop)
  {
    Object msg = objectAt(stackSize-1);
    if (stackSize == oldtop)
    {
      stacksetsize(oldtop + 1);
    }
    switch (errcode)
    {
      case ERRMEM:
        stack[oldtop].r = MEMERRMSG;
        break;

      case ERRERR:
        stack[oldtop].r = "error in error handling";
        break;

      case ERRFILE:
      case ERRRUN:
      case ERRSYNTAX:
        setObjectAt(msg, oldtop);
        break;
    }
    stacksetsize(oldtop+1);
  }

  String dStackDumpString()
  {
// simple lua stack dump so we have a chance to debug...	
	int i=0;
	String s="";

	for(i=-1;i>-4;i--)
	{
		s=s+"\n"+toString(value(i));
	}
	//	s=s+" **["+civ.size()+"]** ";


	String s2;
	for(i=0;i<civ.size();i++)
	{
		s2=where(i);
		s=s+"\n"+s2;
	}
	
	return s;
  }
  void dThrow(int status)
  {

	
    throw new LuaError(status,dStackDumpString());
//    throw new RuntimeException( s );
  }


  //////////////////////////////////////////////////////////////////////
  // Func

  // Methods equivalent to the file lfunc.c.  Prefixed with f.

  /** Equivalent of luaF_close.  All open upvalues referencing stack
   * slots level or higher are closed.
   * @param level  Absolute stack index.
   */
  private void fClose(int level)
  {
    int i = openupval.size();
    while (--i >= 0)
    {
      UpVal uv = (UpVal)openupval.elementAt(i);
      if (uv.offset() < level)
      {
        break;
      }
      uv.close();
    }
    openupval.setSize(i+1);
    return;
  }

  private UpVal fFindupval(int idx)
  {
    /*
     * We search from the end of the Vector towards the beginning,
     * looking for an UpVal for the required stack-slot.
     */
    int i = openupval.size();
    while (--i >= 0)
    {
      UpVal uv = (UpVal)openupval.elementAt(i);
      if (uv.offset() == idx)
      {
        return uv;
      }
      if (uv.offset() < idx)
      {
        break;
      }
    }
    // i points to be position _after_ which we want to insert a new
    // UpVal (it's -1 when we want to insert at the beginning).
    UpVal uv = new UpVal(idx, stack[idx]);
    openupval.insertElementAt(uv, i+1);
    return uv;
  }


  //////////////////////////////////////////////////////////////////////
  // Debug

  // Methods equivalent to the file ldebug.c.  Prefixed with g.

  /** <var>p1</var> and <var>p2</var> are operands to a numeric opcode.
   * Corrupts <code>NUMOP[0]</code>.
   * There is the possibility of using <var>p1</var> and <var>p2</var> to
   * identify (for example) for local variable being used in the
   * computation (consider the error message for code like <code>local
   * y='a'; return y+1</code> for example).  Currently the debug info is
   * not used, and this opportunity is wasted (it would require changing
   * or overloading gTypeerror).
   */
  private void gAritherror(Slot p1, Slot p2)
  {
    if (!tonumber(p1, NUMOP))
    {
      p2 = p1;  // first operand is wrong
    }
    gTypeerror(p2, "perform arithmetic on");
  }

  /** <var>p1</var> and <var>p2</var> are absolute stack indexes. */
  private void gConcaterror(int p1, int p2)
  {
    if (stack[p1].r instanceof String)
    {
      p1 = p2;
    }
    // assert !(p1 instanceof String);
    gTypeerror(stack[p1], "concatenate");
  }

  boolean gCheckcode(Proto p)
  {
    // :todo: implement me.
    return true ;
  }

  private int gErrormsg(Object message)
  {
    push(message);
    if (errfunc != null)        // is there an error handling function
    {
      if (!isFunction(errfunc))
      {
        dThrow(ERRERR);
      }
      insert(errfunc, getTop());        // push function (under error arg)
      vmCall(stackSize-2, 1);        // call it
    }
    dThrow(ERRRUN);
    // NOTREACHED
    return 0;
  }

  private boolean gOrdererror(Slot p1, Slot p2)
  {
    String t1 = typeName(type(p1));
    String t2 = typeName(type(p2));
    if (t1.charAt(2) == t2.charAt(2))
    {
      gRunerror("attempt to compare two " + t1 + "values");
    }
    else
    {
      gRunerror("attempt to compare " + t1 + " with " + t2);
    }
    // NOTREACHED
    return false;
  }

  void gRunerror(String s)
  {
    gErrormsg(s);
  }

  private void gTypeerror(Object o, String op)
  {
    String t = typeName(type(o));
    gRunerror("attempt to " + op + " a " + t + " value");
  }

  private void gTypeerror(Slot p, String op)
  {
    // :todo: PUC-Rio searches the stack to see if the value (which may
    // be a reference to stack cell) is a local variable.
    // For now we cop out and just call gTypeerror(Object, String)
    gTypeerror(p.asObject(), op);
  }


  //////////////////////////////////////////////////////////////////////
  // Object

  // Methods equivalent to the file lobject.c.  Prefixed with o.

  private static final int IDSIZE = 60;
  /**
   * @return a string no longer than IDSIZE.
   */
  static String oChunkid(String source)
  {
    int len = IDSIZE;
    if (source.startsWith("="))
    {
      if(source.length() < IDSIZE+1)
      {
        return source.substring(1);
      }
      else
      {
        return source.substring(1, 1+len);
      }
    }
    // else  "source" or "...source"
    if (source.startsWith("@"))
    {
      source = source.substring(1);
      len -= " '...' ".length();
      int l = source.length();
      if (l > len)
      {
        return "..." +  // get last part of file name
            source.substring(source.length()-len, source.length());
      }
      return source;
    }
    // else  [string "string"]
    int l = source.indexOf('\n');
    if (l == -1)
    {
      l = source.length();
    }
    len -= " [string \"...\"] ".length();
    if (l > len)
    {
      l = len;
    }
    StringBuffer buf = new StringBuffer();
    buf.append("[string \"");
    buf.append(source.substring(0, l));
    if (source.length() > l)    // must truncate
    {
      buf.append("...");
    }
    buf.append("\"]");
    return buf.toString();
  }

  /**
   * Equivalent to luaO_fb2int.
   * @see Syntax#oInt2fb
   */
  private static int oFb2int(int x)
  {
    int e = (x >>> 3) & 31;
    if (e == 0)
    {
      return x;
    }
    return ((x&7)+8) << (e-1);
  }

  /** Equivalent to luaO_rawequalObj. */
  private static boolean oRawequal(Object a, Object b)
  {
    // see also vmEqual
    if (NIL == a)
    {
      return NIL == b;
    }
    // Now a is not null, so a.equals() is a valid call.
    // Numbers (Doubles), Booleans, Strings all get compared by value,
    // as they should; tables, functions, get compared by identity as
    // they should.
    return a.equals(b);
  }

  /** Equivalent to luaO_str2d. */
  private static boolean oStr2d(String s, double[] out)
  {
    // :todo: using try/catch may be too slow.  In which case we'll have
    // to recognise the valid formats first.
    try
    {
      out[0] = Double.parseDouble(s);
      return true;
    }
    catch (NumberFormatException e0_)
    {
      try
      {
        // Attempt hexadecimal conversion.
        // :todo: using String.trim is not strictly accurate, because it
        // trims other ASCII control characters as well as whitespace.
        s = s.trim().toUpperCase();
        if (s.startsWith("0X"))
        {
          s = s.substring(2);
        }
        else if (s.startsWith("-0X"))
        {
          s = "-" + s.substring(3);
        }
        else
        {
          return false;
        }
        out[0] = Integer.parseInt(s, 16);
        return true;
      }
      catch (NumberFormatException e1_)
      {
        return false;
      }
    }
  }


  ////////////////////////////////////////////////////////////////////////
  // VM

  // Most of the methods in this section are equivalent to the files
  // lvm.c and ldo.c from PUC-Rio.  They're mostly prefixed with vm as
  // well.

  private static final int PCRLUA =     0;
  private static final int PCRJ =       1;
  private static final int PCRYIELD =   2;

  // Instruction decomposition.

  // There follows a series of methods that extract the various fields
  // from a VM instruction.  See lopcodes.h from PUC-Rio.
  // :todo: Consider replacing with m4 macros (or similar).
  // A brief overview of the instruction format:
  // Logically an instruction has an opcode (6 bits), op, and up to
  // three fields using one of three formats:
  // A B C  (8 bits, 9 bits, 9 bits)
  // A Bx   (8 bits, 18 bits)
  // A sBx  (8 bits, 18 bits signed - excess K)
  // Some instructions do not use all the fields (EG OP_UNM only uses A
  // and B).
  // When packed into a word (an int in Jill) the following layouts are
  // used:
  //  31 (MSB)    23 22          14 13         6 5      0 (LSB)
  // +--------------+--------------+------------+--------+
  // | B            | C            | A          | OPCODE |
  // +--------------+--------------+------------+--------+
  //
  // +--------------+--------------+------------+--------+
  // | Bx                          | A          | OPCODE |
  // +--------------+--------------+------------+--------+
  //
  // +--------------+--------------+------------+--------+
  // | sBx                         | A          | OPCODE |
  // +--------------+--------------+------------+--------+

  static final int NO_REG = 0xff;       // SIZE_A == 8, (1 << 8)-1

  // Hardwired values for speed.
  /** Equivalent of macro GET_OPCODE */
  static int OPCODE(int instruction)
  {
    // POS_OP == 0 (shift amount)
    // SIZE_OP == 6 (opcode width)
    return instruction & 0x3f;
  }

  /** Equivalent of macro GET_OPCODE */
  static int SET_OPCODE(int i, int op)
  {
    // POS_OP == 0 (shift amount)
    // SIZE_OP == 6 (opcode width)
    return (i & ~0x3F) | (op & 0x3F);
  }

  /** Equivalent of macro GETARG_A */
  static int ARGA(int instruction)
  {
    // POS_A == POS_OP + SIZE_OP == 6 (shift amount)
    // SIZE_A == 8 (operand width)
    return (instruction >>> 6) & 0xff;
  }

  static int SETARG_A(int i, int u)
  {
    return (i & ~(0xff << 6)) | ((u & 0xff) << 6);
  }

  /** Equivalent of macro GETARG_B */
  static int ARGB(int instruction)
  {
    // POS_B == POS_OP + SIZE_OP + SIZE_A + SIZE_C == 23 (shift amount)
    // SIZE_B == 9 (operand width)
    /* No mask required as field occupies the most significant bits of a
     * 32-bit int. */
    return (instruction >>> 23);
  }

  static int SETARG_B(int i, int b)
  {
    return (i & ~(0x1ff << 23)) | ((b & 0x1ff) << 23);
  }

  /** Equivalent of macro GETARG_C */
  static int ARGC(int instruction)
  {
    // POS_C == POS_OP + SIZE_OP + SIZE_A == 14 (shift amount)
    // SIZE_C == 9 (operand width)
    return (instruction >>> 14) & 0x1ff;
  }

  static int SETARG_C(int i, int c)
  {
    return (i & ~(0x1ff << 14)) | ((c & 0x1ff) << 14);
  }

  /** Equivalent of macro GETARG_Bx */
  static int ARGBx(int instruction)
  {
    // POS_Bx = POS_C == 14
    // SIZE_Bx == SIZE_C + SIZE_B == 18
    /* No mask required as field occupies the most significant bits of a
     * 32 bit int. */
    return (instruction >>> 14);
  }

  static int SETARG_Bx(int i, int bx)
  {
    return (i & 0x3fff) | (bx << 14) ;
  }


  /** Equivalent of macro GETARG_sBx */
  static int ARGsBx(int instruction)
  {
    // As ARGBx but with (2**17-1) subtracted.
    return (instruction >>> 14) - MAXARG_sBx;
  }

  static int SETARG_sBx(int i, int bx)
  {
    return (i & 0x3fff) | ((bx+MAXARG_sBx) << 14) ;  // CHECK THIS IS RIGHT
  }

  static boolean ISK(int field)
  {
    // The "is constant" bit position depends on the size of the B and C
    // fields (required to be the same width).
    // SIZE_B == 9
    return field >= 0x100;
  }

  /**
   * Near equivalent of macros RKB and RKC.  Note: non-static as it
   * requires stack and base instance members.  Stands for "Register or
   * Konstant" by the way, it gets value from either the register file
   * (stack) or the constant array (k).
   */
  private Slot RK(Slot[] k, int field)
  {
    if (ISK(field))
    {
      return k[field & 0xff];
    }
    return stack[base + field];
  }

  /**
   * Slower version of RK that does not receive the constant array.  Not
   * recommend for routine use, but is used by some error handling code
   * to avoid having a constant array passed around too much.
   */
  private Slot RK(int field)
  {
    LuaFunction function = (LuaFunction)stack[ci().function()].r;
    Slot[] k = function.proto().constant();
    return RK(k, field);
  }

  // CREATE functions are required by FuncState, so default access.
  static int CREATE_ABC(int o, int a, int b, int c)
  {
    // POS_OP == 0
    // POS_A == 6
    // POS_B == 23
    // POS_C == 14
    return o | (a << 6) | (b << 23) | (c << 14);
  }

  static int CREATE_ABx(int o, int a, int bc)
  {
    // POS_OP == 0
    // POS_A == 6
    // POS_Bx == POS_C == 14
    return o | (a << 6) | (bc << 14);
  }

  // opcode enumeration.
  // Generated by a script:
  // awk -f opcode.awk < lopcodes.h
  // and then pasted into here.
  // Made default access so that code generation, in FuncState, can see
  // the enumeration as well.

  static final int OP_MOVE = 0;
  static final int OP_LOADK = 1;
  static final int OP_LOADBOOL = 2;
  static final int OP_LOADNIL = 3;
  static final int OP_GETUPVAL = 4;
  static final int OP_GETGLOBAL = 5;
  static final int OP_GETTABLE = 6;
  static final int OP_SETGLOBAL = 7;
  static final int OP_SETUPVAL = 8;
  static final int OP_SETTABLE = 9;
  static final int OP_NEWTABLE = 10;
  static final int OP_SELF = 11;
  static final int OP_ADD = 12;
  static final int OP_SUB = 13;
  static final int OP_MUL = 14;
  static final int OP_DIV = 15;
  static final int OP_MOD = 16;
  static final int OP_POW = 17;
  static final int OP_UNM = 18;
  static final int OP_NOT = 19;
  static final int OP_LEN = 20;
  static final int OP_CONCAT = 21;
  static final int OP_JMP = 22;
  static final int OP_EQ = 23;
  static final int OP_LT = 24;
  static final int OP_LE = 25;
  static final int OP_TEST = 26;
  static final int OP_TESTSET = 27;
  static final int OP_CALL = 28;
  static final int OP_TAILCALL = 29;
  static final int OP_RETURN = 30;
  static final int OP_FORLOOP = 31;
  static final int OP_FORPREP = 32;
  static final int OP_TFORLOOP = 33;
  static final int OP_SETLIST = 34;
  static final int OP_CLOSE = 35;
  static final int OP_CLOSURE = 36;
  static final int OP_VARARG = 37;

  // end of instruction decomposition

  static final int SIZE_C = 9;
  static final int SIZE_B = 9;
  static final int SIZE_Bx = SIZE_C + SIZE_B;
  static final int SIZE_A = 8;

  static final int SIZE_OP = 6;

  static final int POS_OP = 0;
  static final int POS_A = POS_OP + SIZE_OP;
  static final int POS_C = POS_A + SIZE_A;
  static final int POS_B = POS_C + SIZE_C;
  static final int POS_Bx = POS_C;

  static final int MAXARG_Bx = (1<<SIZE_Bx)-1;
  static final int MAXARG_sBx = MAXARG_Bx>>1;    // `sBx' is signed


  static final int MAXARG_A = (1<<SIZE_A)-1;
  static final int MAXARG_B = (1<<SIZE_B)-1;
  static final int MAXARG_C = (1<<SIZE_C)-1;

  /* this bit 1 means constant (0 means register) */
  static final int BITRK = 1 << (SIZE_B - 1) ;
  static final int MAXINDEXRK = BITRK - 1 ;


  /**
   * Equivalent of luaD_call.
   * @param func  absolute stack index of function to call.
   * @param r     number of required results.
   */
  private void vmCall(int func, int r)
  {
    ++nCcalls;
    if (vmPrecall(func, r) == PCRLUA)
    {
      vmExecute(1);
    }
    --nCcalls;
  }

  /** Equivalent of luaV_concat. */
  private void vmConcat(int total, int last)
  {
    do
    {
      int top = base + last + 1;
      int n = 2;  // number of elements handled in this pass (at least 2)
      if (!tostring(top-2)|| !tostring(top-1))
      {
        if (!call_binTM(stack[top-2], stack[top-1],
            stack[top-2], "__concat"))
        {
          gConcaterror(top-2, top-1);
        }
      }
      else if (((String)stack[top-1].r).length() > 0)
      {
        int tl = ((String)stack[top-1].r).length();
        for (n = 1; n < total && tostring(top-n-1); ++n)
        {
          tl += ((String)stack[top-n-1].r).length();
          if (tl < 0)
          {
            gRunerror("string length overflow");
          }
        }
        StringBuffer buffer = new StringBuffer(tl);
        for (int i=n; i>0; i--)         // concat all strings
        {
          buffer.append(stack[top-i].r);
        }
        stack[top-n].r = buffer.toString();
      }
      total -= n-1;     // got n strings to create 1 new
      last -= n-1;
    } while (total > 1); // repeat until only 1 result left
  }

  /**
   * Primitive for testing Lua equality of two values.  Equivalent of
   * PUC-Rio's <code>equalobj</code> macro.
   * In the loosest sense, this is the equivalent of
   * <code>luaV_equalval</code>.
   */
  private boolean vmEqual(Slot a, Slot b)
  {
    // Deal with number case first
    if (NUMBER == a.r)
    {
      if (NUMBER != b.r)
      {
        return false;
      }
      return a.d == b.d;
    }
    // Now we're only concerned with the .r field.
    return vmEqualRef(a.r, b.r);
  }

  /**
   * Part of {@link #vmEqual}.  Compares the reference part of two
   * Slot instances.  That is, compares two Lua values, as long as
   * neither is a number.
   */
  private boolean vmEqualRef(Object a, Object b)
  {
    if (a.equals(b))
    {
      return true;
    }
    if (a.getClass() != b.getClass())
    {
      return false;
    }
    // Same class, but different objects.
    if (a instanceof LuaJavaCallback ||
        a instanceof LuaTable)
    {
      // Resort to metamethods.
      Object tm = get_compTM(getMetatable(a), getMetatable(b), "__eq");
      if (NIL == tm)    // no TM?
      {
        return false;
      }
      Slot s = new Slot();
      callTMres(s, tm, a, b);   // call TM
      return !isFalse(s.r);
    }
    return false;
  }

  /**
   * Array of numeric operands.  Used when converting strings to numbers
   * by an arithmetic opcode (ADD, SUB, MUL, DIV, MOD, POW, UNM).
   */
  private static final double[] NUMOP = new double[2];

  /** The core VM execution engine. */
  private void vmExecute(int nexeccalls)
  {
    // This labelled while loop is used to simulate the effect of C's
    // goto.  The end of the while loop is never reached.  The beginning
    // of the while loop is branched to using a "continue reentry;"
    // statement (when a Lua function is called or returns).
reentry:
    while (true)
    {
      // assert stack[ci.function()].r instanceof LuaFunction;
      LuaFunction function = (LuaFunction)stack[ci().function()].r;
      Proto proto = function.proto();
      int[] code = proto.code();
      Slot[] k = proto.constant();
      int pc = savedpc;

      while (true)        // main loop of interpreter
      {

        // Where the PUC-Rio code used the Protect macro, this has been
        // replaced with "savedpc = pc" and a "// Protect" comment.

        // Where the PUC-Rio code used the dojump macro, this has been
        // replaced with the equivalent increment of the pc and a
        // "//dojump" comment.

        int i = code[pc++];       // VM instruction.
        // :todo: line hook
        if ((hookmask & MASKCOUNT) != 0 && --hookcount == 0)
        {
          traceexec(pc);
          if (status == YIELD)  // did hook yield?
          {
            savedpc = pc - 1;
            return;
          }
          // base = this.base
        }

        int a = ARGA(i);          // its A field.
        Slot rb;
        Slot rc;

        switch (OPCODE(i))
        {
          case OP_MOVE:
            stack[base+a].r = stack[base+ARGB(i)].r;
            stack[base+a].d = stack[base+ARGB(i)].d;
            continue;
          case OP_LOADK:
            stack[base+a].r = k[ARGBx(i)].r;
            stack[base+a].d = k[ARGBx(i)].d;
            continue;
          case OP_LOADBOOL:
            stack[base+a].r = valueOfBoolean(ARGB(i) != 0);
            if (ARGC(i) != 0)
            {
              ++pc;
            }
            continue;
          case OP_LOADNIL:
          {
            int b = base + ARGB(i);
            do
            {
              stack[b--].r = NIL;
            } while (b >= base + a);
            continue;
          }
          case OP_GETUPVAL:
          {
            int b = ARGB(i);
            // :todo: optimise path
            setObjectAt(function.upVal(b).getValue(), base+a);
            continue;
          }
          case OP_GETGLOBAL:
            rb = k[ARGBx(i)];
            // assert rb instance of String;
            savedpc = pc; // Protect
            vmGettable(function.getEnv(), rb, stack[base+a]);
            continue;
          case OP_GETTABLE:
          {
            savedpc = pc; // Protect
            Object h = stack[base+ARGB(i)].asObject();
            vmGettable(h, RK(k, ARGC(i)), stack[base+a]);
            continue;
          }
          case OP_SETUPVAL:
          {
            UpVal uv = function.upVal(ARGB(i));
            uv.setValue(objectAt(base+a));
            continue;
          }
          case OP_SETGLOBAL:
            savedpc = pc; // Protect
            // :todo: consider inlining objectAt
            vmSettable(function.getEnv(), k[ARGBx(i)],
                objectAt(base+a));
            continue;
          case OP_SETTABLE:
          {
            savedpc = pc; // Protect
            Object t = stack[base+a].asObject();
            vmSettable(t, RK(k, ARGB(i)), RK(k, ARGC(i)).asObject());
            continue;
          }
          case OP_NEWTABLE:
          {
            int b = ARGB(i);
            int c = ARGC(i);
            stack[base+a].r = new LuaTable(oFb2int(b), oFb2int(c));
            continue;
          }
          case OP_SELF:
          {
            int b = ARGB(i);
            rb = stack[base+b];
            stack[base+a+1].r = rb.r;
            stack[base+a+1].d = rb.d;
            savedpc = pc; // Protect
            vmGettable(rb.asObject(), RK(k, ARGC(i)), stack[base+a]);
            continue;
          }
          case OP_ADD:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double sum = rb.d + rc.d;
              stack[base+a].d = sum;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double sum = NUMOP[0] + NUMOP[1];
              stack[base+a].d = sum;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__add"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_SUB:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double difference = rb.d - rc.d;
              stack[base+a].d = difference;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double difference = NUMOP[0] - NUMOP[1];
              stack[base+a].d = difference;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__sub"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_MUL:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double product = rb.d * rc.d;
              stack[base+a].d = product;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double product = NUMOP[0] * NUMOP[1];
              stack[base+a].d = product;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__mul"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_DIV:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double quotient = rb.d / rc.d;
              stack[base+a].d = quotient;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double quotient = NUMOP[0] / NUMOP[1];
              stack[base+a].d = quotient;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__div"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_MOD:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double modulus = modulus(rb.d, rc.d);
              stack[base+a].d = modulus;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double modulus = modulus(NUMOP[0], NUMOP[1]);
              stack[base+a].d = modulus;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__mod"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_POW:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (rb.r == NUMBER && rc.r == NUMBER)
            {
              double result = iNumpow(rb.d, rc.d);
              stack[base+a].d = result;
              stack[base+a].r = NUMBER;
            }
            else if (toNumberPair(rb, rc, NUMOP))
            {
              double result = iNumpow(NUMOP[0], NUMOP[1]);
              stack[base+a].d = result;
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rc, stack[base+a], "__pow"))
            {
              gAritherror(rb, rc);
            }
            continue;
          case OP_UNM:
            rb = stack[base+ARGB(i)];
            if (rb.r == NUMBER)
            {
              stack[base+a].d = -rb.d;
              stack[base+a].r = NUMBER;
            }
            else if (tonumber(rb, NUMOP))
            {
              stack[base+a].d = -NUMOP[0];
              stack[base+a].r = NUMBER;
            }
            else if (!call_binTM(rb, rb, stack[base+a], "__unm"))
            {
              gAritherror(rb, rb);
            }
            continue;
          case OP_NOT:
          {
            // All numbers are treated as true, so no need to examine
            // the .d field.
            Object ra = stack[base+ARGB(i)].r;
            stack[base+a].r = valueOfBoolean(isFalse(ra));
            continue;
          }
          case OP_LEN:
            rb = stack[base+ARGB(i)];
            if (rb.r instanceof LuaTable)
            {
              LuaTable t = (LuaTable)rb.r;
              stack[base+a].d = t.getn();
              stack[base+a].r = NUMBER;
              continue;
            }
            else if (rb.r instanceof String)
            {
              String s = (String)rb.r;
              stack[base+a].d = s.length();
              stack[base+a].r = NUMBER;
              continue;
            }
            savedpc = pc; // Protect
            if (!call_binTM(rb, rb, stack[base+a], "__len"))
            {
              gTypeerror(rb, "get length of");
            }
            continue;
          case OP_CONCAT:
          {
            int b = ARGB(i);
            int c = ARGC(i);
            savedpc = pc; // Protect
            // :todo: The compiler assumes that all
            // stack locations _above_ b end up with junk in them.  In
            // which case we can improve the speed of vmConcat (by not
            // converting each stack slot, but simply using
            // StringBuffer.append on whatever is there).
            vmConcat(c - b + 1, c);
            stack[base+a].r = stack[base+b].r;
            stack[base+a].d = stack[base+b].d;
            continue;
          }
          case OP_JMP:
            // dojump
            pc += ARGsBx(i);
            continue;
          case OP_EQ:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            if (vmEqual(rb, rc) == (a != 0))
            {
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          case OP_LT:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            savedpc = pc; // Protect
            if (vmLessthan(rb, rc) == (a != 0))
            {
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          case OP_LE:
            rb = RK(k, ARGB(i));
            rc = RK(k, ARGC(i));
            savedpc = pc; // Protect
            if (vmLessequal(rb, rc) == (a != 0))
            {
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          case OP_TEST:
            if (isFalse(stack[base+a].r) != (ARGC(i) != 0))
            {
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          case OP_TESTSET:
            rb = stack[base+ARGB(i)];
            if (isFalse(rb.r) != (ARGC(i) != 0))
            {
              stack[base+a].r = rb.r;
              stack[base+a].d = rb.d;
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          case OP_CALL:
          {
            int b = ARGB(i);
            int nresults = ARGC(i) - 1;
            if (b != 0)
            {
              stacksetsize(base+a+b);
            }
            savedpc = pc;
            switch (vmPrecall(base+a, nresults))
            {
              case PCRLUA:
                nexeccalls++;
                continue reentry;
              case PCRJ:
                // Was Java function called by precall, adjust result
                if (nresults >= 0)
                {
                  stacksetsize(ci().top());
                }
                continue;
              default:
                return; // yield
            }
          }
          case OP_TAILCALL:
          {
            int b = ARGB(i);
            if (b != 0)
            {
              stacksetsize(base+a+b);
            }
            savedpc = pc;
            // assert ARGC(i) - 1 == MULTRET
            switch (vmPrecall(base+a, MULTRET))
            {
              case PCRLUA:
              {
                // tail call: put new frame in place of previous one.
                CallInfo ci = (CallInfo)civ.elementAt(civ.size()-2);
                int func = ci.function();
                CallInfo fci = ci();    // Fresh CallInfo
                int pfunc = fci.function();
                fClose(ci.base());
                base = func + (fci.base() - pfunc);
                int aux;        // loop index is used after loop ends
                for (aux=0; pfunc+aux < stackSize; ++aux)
                {
                  // move frame down
                  stack[func+aux].r = stack[pfunc+aux].r;
                  stack[func+aux].d = stack[pfunc+aux].d;
                }
                stacksetsize(func+aux);        // correct top
                // assert stackSize == base + ((LuaFunction)stack[func]).proto().maxstacksize();
                ci.tailcall(base, stackSize);
                dec_ci();       // remove new frame.
                continue reentry;
              }
              case PCRJ:        // It was a Java function
              {
                continue;
              }
              default:
              {
                return; // yield
              }
            }
          }
          case OP_RETURN:
          {
            fClose(base);
            int b = ARGB(i);
            if (b != 0)
            {
              int top = a + b - 1;
              stacksetsize(base + top);
            }
            savedpc = pc;
            // 'adjust' replaces aliased 'b' in PUC-Rio code.
            boolean adjust = vmPoscall(base+a);
            if (--nexeccalls == 0)
            {
              return;
            }
            if (adjust)
            {
              stacksetsize(ci().top());
            }
            continue reentry;
          }
          case OP_FORLOOP:
          {
            double step = stack[base+a+2].d;
            double idx = stack[base+a].d + step;
            double limit = stack[base+a+1].d;
            if ((0 < step && idx <= limit) ||
                (step <= 0 && limit <= idx))
            {
              // dojump
              pc += ARGsBx(i);
              stack[base+a].d = idx;    // internal index
              stack[base+a].r = NUMBER;
              stack[base+a+3].d = idx;  // external index
              stack[base+a+3].r = NUMBER;
            }
            continue;
          }
          case OP_FORPREP:
          {
            int init = base+a;
            int plimit = base+a+1;
            int pstep = base+a+2;
            savedpc = pc;       // next steps may throw errors
            if (!tonumber(init))
            {
              gRunerror("'for' initial value must be a number");
            }
            else if (!tonumber(plimit))
            {
              gRunerror("'for' limit must be a number");
            }
            else if (!tonumber(pstep))
            {
              gRunerror("'for' step must be a number");
            }
            double step = stack[pstep].d;
            double idx = stack[init].d - step;
            stack[init].d = idx;
            stack[init].r = NUMBER;
            // dojump
            pc += ARGsBx(i);
            continue;
          }
          case OP_TFORLOOP:
          {
            int cb = base+a+3;  // call base
            stack[cb+2].r = stack[base+a+2].r;
            stack[cb+2].d = stack[base+a+2].d;
            stack[cb+1].r = stack[base+a+1].r;
            stack[cb+1].d = stack[base+a+1].d;
            stack[cb].r = stack[base+a].r;
            stack[cb].d = stack[base+a].d;
            stacksetsize(cb+3);
            savedpc = pc; // Protect
            vmCall(cb, ARGC(i));
            stacksetsize(ci().top());
            if (NIL != stack[cb].r)     // continue loop
            {
              stack[cb-1].r = stack[cb].r;
              stack[cb-1].d = stack[cb].d;
              // dojump
              pc += ARGsBx(code[pc]);
            }
            ++pc;
            continue;
          }
          case OP_SETLIST:
          {
            int n = ARGB(i);
            int c = ARGC(i);
            boolean setstack = false;
            if (0 == n)
            {
              n = (stackSize - (base + a)) - 1;
              setstack = true;
            }
            if (0 == c)
            {
              c = code[pc++];
            }
            LuaTable t = (LuaTable)stack[base+a].r;
            int last = ((c-1)*LFIELDS_PER_FLUSH) + n;
            // :todo: consider expanding space in table
            for (; n > 0; n--)
            {
              Object val = objectAt(base+a+n);
              t.putnum(last--, val);
            }
            if (setstack)
            {
              stacksetsize(ci().top());
            }
            continue;
          }
          case OP_CLOSE:
            fClose(base+a);
            continue;
          case OP_CLOSURE:
          {
            Proto p = function.proto().proto()[ARGBx(i)];
            int nup = p.nups();
            UpVal[] up = new UpVal[nup];
            for (int j=0; j<nup; j++, pc++)
            {
              int in = code[pc];
              if (OPCODE(in) == OP_GETUPVAL)
              {
                up[j] = function.upVal(ARGB(in));
              }
              else
              {
                // assert OPCODE(in) == OP_MOVE;
                up[j] = fFindupval(base + ARGB(in));
              }
            }
            LuaFunction nf = new LuaFunction(p, up, function.getEnv());
            stack[base+a].r = nf;
            continue;
          }
          case OP_VARARG:
          {
            int b = ARGB(i)-1;
            int n = (base - ci().function()) -
                function.proto().numparams() - 1;
            if (b == MULTRET)
            {
              // :todo: Protect
              // :todo: check stack
              b = n;
              stacksetsize(base+a+n);
            }
            for (int j=0; j<b; ++j)
            {
              if (j < n)
              {
                Slot src = stack[base - n + j];
                stack[base+a+j].r = src.r;
                stack[base+a+j].d = src.d;
              }
              else
              {
                stack[base+a+j].r = NIL;
              }
            }
            continue;
          }
        } /* switch */
      } /* while */
    } /* reentry: while */
  }

  static double iNumpow(double a, double b)
  {
    // :todo: this needs proper checking for boundary cases
    // EG, is currently wrong for (-0)^2.
    boolean invert = b < 0.0 ;
    if (invert) b = -b ;
    if (a == 0.0)
      return invert ? Double.NaN : a ;
    double result = 1.0 ;
    int ipow = (int) b ;
    b -= ipow ;
    double t = a ;
    while (ipow > 0)
    {
      if ((ipow & 1) != 0)
        result *= t ;
      ipow >>= 1 ;
      t = t*t ;
    }
    if (b != 0.0) // integer only case, save doing unnecessary work
    {
      if (a < 0.0)  // doesn't work if a negative (complex result!)
        return Double.NaN ;
      t = Math.sqrt(a) ;
      double half = 0.5 ;
      while (b > 0.0)
      {
        if (b >= half)
        {
          result = result * t ;
          b -= half ;
        }
        b = b+b ;
        t = Math.sqrt(t) ;
        if (t == 1.0)
          break ;
      }
    }
    return invert ?  1.0 / result : result ;
  }

  /** Equivalent of luaV_gettable. */
  private void vmGettable(Object t, Slot key, Slot val)
  {
    Object tm;
    for (int loop = 0; loop < MAXTAGLOOP; ++loop)
    {
      if (t instanceof LuaTable)        // 't' is a table?
      {
        LuaTable h = (LuaTable)t;
        h.getlua(key, SPARE_SLOT);

        if (SPARE_SLOT.r != NIL)
        {
          val.r = SPARE_SLOT.r;
          val.d = SPARE_SLOT.d;
          return;
        }
        tm = tagmethod(h, "__index");
        if (tm == NIL)
        {
          val.r = NIL;
          return;
        }
        // else will try the tag method
      }
      else
      {
        tm = tagmethod(t, "__index");
        if (tm == NIL)
          gTypeerror(t, "index");
      }
      if (isFunction(tm))
      {
        SPARE_SLOT.setObject(t);
        callTMres(val, tm, SPARE_SLOT, key);
        return;
      }
      t = tm;     // else repeat with 'tm'
    }
    gRunerror("loop in gettable");
  }

  /** Equivalent of luaV_lessthan. */
  private boolean vmLessthan(Slot l, Slot r)
  {
    if (l.r.getClass() != r.r.getClass())
    {
      gOrdererror(l, r);
    }
    else if (l.r == NUMBER)
    {
      return l.d < r.d;
    }
    else if (l.r instanceof String)
    {
      // :todo: PUC-Rio use strcoll, maybe we should use something
      // equivalent.
      return ((String)l.r).compareTo((String)r.r) < 0;
    }
    int res = call_orderTM(l, r, "__lt");
    if (res >= 0)
    {
      return res != 0;
    }
    return gOrdererror(l, r);
  }

  /** Equivalent of luaV_lessequal. */
  private boolean vmLessequal(Slot l, Slot r)
  {
    if (l.r.getClass() != r.r.getClass())
    {
      gOrdererror(l, r);
    }
    else if (l.r == NUMBER)
    {
      return l.d <= r.d;
    }
    else if (l.r instanceof String)
    {
      return ((String)l.r).compareTo((String)r.r) <= 0;
    }
    int res = call_orderTM(l, r, "__le");       // first try 'le'
    if (res >= 0)
    {
      return res != 0;
    }
    res = call_orderTM(r, l, "__lt");   // else try 'lt'
    if (res >= 0)
    {
      return res == 0;
    }
    return gOrdererror(l, r);
  }

  /**
   * Equivalent of luaD_poscall.
   * @param firstResult  stack index (absolute) of the first result
   */
  private boolean vmPoscall(int firstResult)
  {
    // :todo: call hook
    CallInfo lci; // local copy, for faster access
    lci = dec_ci();
    // Now (as a result of the dec_ci call), lci is the CallInfo record
    // for the current function (the function executing an OP_RETURN
    // instruction), and this.ci is the CallInfo record for the function
    // we are returning to.
    int res = lci.res();
    int wanted = lci.nresults();        // Caution: wanted could be == MULTRET
    CallInfo cci = ci();        // Continuation CallInfo
    base = cci.base();
    savedpc = cci.savedpc();
    // Move results (and pad with nils to required number if necessary)
    int i = wanted;
    int top = stackSize;
    // The movement is always downwards, so copying from the top-most
    // result first is always correct.
    while (i != 0 && firstResult < top)
    {
      stack[res].r = stack[firstResult].r;
      stack[res].d = stack[firstResult].d;
      ++res;
      ++firstResult;
      i--;
    }
    if (i > 0)
    {
      stacksetsize(res+i);
    }
    // :todo: consider using two stacksetsize calls to nil out
    // remaining required results.
    while (i-- > 0)
    {
      stack[res++].r = NIL;
    }
    stacksetsize(res);
    return wanted != MULTRET;
  }

  /**
   * Equivalent of LuaD_precall.  This method expects that the arguments
   * to the function are placed above the function on the stack.
   * @param func  absolute stack index of the function to call.
   * @param r     number of results expected.
   */
  private int vmPrecall(int func, int r)
  {
    Object faso;        // Function AS Object
    faso = stack[func].r;
    if (!isFunction(faso))
    {
      faso = tryfuncTM(func);
    }
    ci().setSavedpc(savedpc);
    if (faso instanceof LuaFunction)
    {
      LuaFunction f = (LuaFunction)faso;
      Proto p = f.proto();
      // :todo: ensure enough stack

      if (!p.isVararg())
      {
        base = func + 1;
        if (stackSize > base + p.numparams())
        {
          // trim stack to the argument list
          stacksetsize(base + p.numparams());
        }
      }
      else
      {
        int nargs = (stackSize - func) - 1;
        base = adjust_varargs(p, nargs);
      }

      int top = base + p.maxstacksize();
      inc_ci(func, base, top, r);

      savedpc = 0;
      // expand stack to the function's max stack size.
      stacksetsize(top);
      // :todo: implement call hook.
      return PCRLUA;
    }
    else if (faso instanceof LuaJavaCallback)
    {
      LuaJavaCallback fj = (LuaJavaCallback)faso;
      // :todo: checkstack (not sure it's necessary)
      base = func + 1;
      inc_ci(func, base, stackSize+MINSTACK, r);
      // :todo: call hook
      int n = 99;
      try
      {
        n = fj.luaFunction(this);
      }
      catch (LuaError e)
      {
        throw e;
      }
      catch (RuntimeException e)
      {
// HAX: this yield seems bad?
//        yield(0);
        throw e;
      }
      if (n < 0)        // yielding?
      {
        return PCRYIELD;
      }
      else
      {
        vmPoscall(stackSize - n);
        return PCRJ;
      }
    }

    throw new IllegalArgumentException();
  }

  /** Equivalent of luaV_settable. */
  private void vmSettable(Object t, Slot key, Object val)
  {
    for (int loop = 0; loop < MAXTAGLOOP; ++loop)
    {
      Object tm;
      if (t instanceof LuaTable) // 't' is a table
      {
        LuaTable h = (LuaTable)t;
        h.getlua(key, SPARE_SLOT);
        if (SPARE_SLOT.r != NIL)   // result is not nil?
        {
          h.putlua(this, key, val);
          return;
        }
        tm = tagmethod(h, "__newindex");
        if (tm == NIL)  // or no TM?
        {
          h.putlua(this, key, val);
          return;
        }
        // else will try the tag method
      }
      else
      {
        tm = tagmethod(t, "__newindex");
        if (tm == NIL)
          gTypeerror(t, "index");
      }
      if (isFunction(tm))
      {
        callTM(tm, t, key, val);
        return;
      }
      t = tm;     // else repeat with 'tm'
    }
    gRunerror("loop in settable");
  }

  /**
   * Printf format item used to convert numbers to strings (in {@link
   * #vmTostring}).  The initial '%' should be not specified.
   */
  private static final String NUMBER_FMT = ".14g";

  private static String vmTostring(Object o)
  {
    if (o instanceof String)
    {
      return (String)o;
    }
    if (!(o instanceof Double))
    {
      return null;
    }
    // Convert number to string.  PUC-Rio abstracts this operation into
    // a macro, lua_number2str.  The macro is only invoked from their
    // equivalent of this code.
    // Formerly this code used Double.toString (and remove any trailing
    // ".0") but this does not give an accurate emulation of the PUC-Rio
    // behaviour which Intuwave require.  So now we use "%.14g" like
    // PUC-Rio.
    // :todo: consider optimisation of making FormatItem an immutable
    // class and keeping a static reference to the required instance
    // (which never changes).  A possible half-way house would be to
    // create a copied instance from an already create prototype
    // instance which would be faster than parsing the format string
    // each time.
    FormatItem f = new FormatItem(null, NUMBER_FMT);
    StringBuffer b = new StringBuffer();
    Double d = (Double)o;
    f.formatFloat(b, d.doubleValue());
    return b.toString();
  }

  /** Equivalent of adjust_varargs in "ldo.c". */
  private int adjust_varargs(Proto p, int actual)
  {
    int nfixargs = p.numparams();
    for (; actual < nfixargs; ++actual)
    {
      stackAdd(NIL);
    }
    // PUC-Rio's LUA_COMPAT_VARARG is not supported here.

    // Move fixed parameters to final position
    int fixed = stackSize - actual;  // first fixed argument
    int newbase = stackSize; // final position of first argument
    for (int i=0; i<nfixargs; ++i)
    {
      // :todo: arraycopy?
      push(stack[fixed+i]);
      stack[fixed+i].r = NIL;
    }
    return newbase;
  }

  /**
   * Does not modify contents of p1 or p2.  Modifies contents of res.
   * @param p1  left hand operand.
   * @param p2  right hand operand.
   * @param res absolute stack index of result.
   * @return false if no tagmethod, true otherwise
   */
  private boolean call_binTM(Slot p1, Slot p2, Slot res, String event)
  {
    Object tm = tagmethod(p1.asObject(), event);        // try first operand
    if (isNil(tm))
    {
      tm = tagmethod(p2.asObject(), event);     // try second operand
    }
    if (!isFunction(tm))
    {
      return false;
    }
    callTMres(res, tm, p1, p2);
    return true;
  }

  /**
   * @return -1 if no tagmethod, 0 false, 1 true
   */
  private int call_orderTM(Slot p1, Slot p2, String event)
  {
    Object tm1 = tagmethod(p1.asObject(), event);
    if (tm1 == NIL)     // not metamethod
    {
      return -1;
    }
    Object tm2 = tagmethod(p2.asObject(), event);
    if (!oRawequal(tm1, tm2))   // different metamethods?
    {
      return -1;
    }
    Slot s = new Slot();
    callTMres(s, tm1, p1, p2);
    return isFalse(s.r) ? 0 : 1;
  }

  private void callTM(Object f, Object p1, Slot p2, Object p3)
  {
    push(f);
    push(p1);
    push(p2);
    push(p3);
    vmCall(stackSize-4, 0);
  }

  private void callTMres(Slot res, Object f, Slot p1, Slot p2)
  {
    push(f);
    push(p1);
    push(p2);
    vmCall(stackSize-3, 1);
    res.r = stack[stackSize-1].r;
    res.d = stack[stackSize-1].d;
    pop(1);
  }

  /**
   * Overloaded version of callTMres used by {@link #vmEqualRef}.
   * Textuall identical, but a different (overloaded) push method is
   * invoked.
   */
  private void callTMres(Slot res, Object f, Object p1, Object p2)
  {
    push(f);
    push(p1);
    push(p2);
    vmCall(stackSize-3, 1);
    res.r = stack[stackSize-1].r;
    res.d = stack[stackSize-1].d;
    pop(1);
  }

  private Object get_compTM(LuaTable mt1, LuaTable mt2, String event)
  {
    if (mt1 == null)
    {
      return NIL;
    }
    Object tm1 = mt1.getlua(event);
    if (isNil(tm1))
    {
      return NIL;       // no metamethod
    }
    if (mt1 == mt2)
    {
      return tm1;       // same metatables => same metamethods
    }
    if (mt2 == null)
    {
      return NIL;
    }
    Object tm2 = mt2.getlua(event);
    if (isNil(tm2))
    {
      return NIL;       // no metamethod
    }
    if (oRawequal(tm1, tm2))    // same metamethods?
    {
      return tm1;
    }
    return NIL;
  }

  /**
   * Gets tagmethod for object.
   * @return method or nil.
   */
  private Object tagmethod(Object o, String event)
  {
    return getMetafield(o, event);
  }

  /** @deprecated DO NOT CALL */
  private Object tagmethod(Slot o, String event)
  {
    throw new IllegalArgumentException("tagmethod called");
  }

  /**
   * Computes the result of Lua's modules operator (%).  Note that this
   * modulus operator does not match Java's %.
   */
  private static double modulus(double x, double y)
  {
    return x - Math.floor(x/y)*y;
  }

  /**
   * Changes the stack size, padding with NIL where necessary, and
   * allocate a new stack array if necessary.
   */
  private void stacksetsize(int n)
  {
    // It is absolutely critical that when the stack changes sizes those
    // elements that are common to both old and new stack are unchanged.

    // First implementation of this simply ensures that the stack array
    // has at least the required size number of elements.
    // :todo: consider policies where the stack may also shrink.
    int old = stackSize;
    if (n > stack.length)
    {
      int newLength = Math.max(n, 2 * stack.length);
      Slot[] newStack = new Slot[newLength];
      // Currently the stack only ever grows, so the number of items to
      // copy is the length of the old stack.
      int toCopy = stack.length;
      System.arraycopy(stack, 0, newStack, 0, toCopy);
      stack = newStack;
    }
    stackSize = n;
    // Nilling out.  The VM requires that fresh stack slots allocated
    // for a new function activation are initialised to nil (which is
    // Lua.NIL, which is not Java null).
    // There are basically two approaches: nil out when the stack grows,
    // or nil out when it shrinks.  Nilling out when the stack grows is
    // slightly simpler, but nilling out when the stack shrinks means
    // that semantic garbage is not retained by the GC.
    // We nil out slots when the stack shrinks, but we also need to make
    // sure they are nil initially.
    // In order to avoid nilling the entire array when we allocate one
    // we maintain a stackhighwater which is 1 more than that largest
    // stack slot that has been nilled.  We use this to nil out stacks
    // slow when we grow.
    if (n <= old)
    {
      // when shrinking
      for(int i=n; i<old; ++i)
      {
        stack[i].r = NIL;
      }
    }
    if (n > stackhighwater)
    {
      // when growing above stackhighwater for the first time
      for (int i=stackhighwater; i<n; ++i)
      {
        stack[i] = new Slot();
        stack[i].r = NIL;
      }
      stackhighwater = n;
    }
  }

  /**
   * Pushes a Lua value onto the stack.
   */
  private void stackAdd(Object o)
  {
    int i = stackSize;
    stacksetsize(i+1);
    stack[i].setObject(o);
  }

  /**
   * Copies a slot into a new space in the stack.
   */
  private void push(Slot p)
  {
    int i = stackSize;
    stacksetsize(i+1);
    stack[i].r = p.r;
    stack[i].d = p.d;
  }

  private void stackInsertAt(Object o, int i)
  {
    int n = stackSize - i;
    stacksetsize(stackSize+1);
    // Copy each slot N into its neighbour N+1.  Loop proceeds from high
    // index slots to lower index slots.
    // A loop from n to 1 copies n slots.
    for (int j=n; j>=1; --j)
    {
      stack[i+j].r = stack[i+j-1].r;
      stack[i+j].d = stack[i+j-1].d;
    }
    stack[i].setObject(o);
  }

  /**
   * Equivalent of macro in ldebug.h.
   */
  private void resethookcount()
  {
    hookcount = basehookcount;
  }

  /**
   * Equivalent of traceexec in lvm.c.
   */
  private void traceexec(int pc)
  {
    int mask = hookmask;
    int oldpc = savedpc;
    savedpc = pc;
    if (mask > MASKLINE)        // instruction-hook set?
    {
      if (hookcount == 0)
      {
        resethookcount();
        dCallhook(HOOKCOUNT, -1);
      }
    }
    // :todo: line hook.
  }

  /**
   * Convert to number.  Returns true if the argument <var>o</var> was
   * converted to a number.  Converted number is placed in <var>out[0]</var>.
   * Returns
   * false if the argument <var>o</var> could not be converted to a number.
   * Overloaded.
   */
  private static boolean tonumber(Slot o, double[] out)
  {
    if (o.r == NUMBER)
    {
      out[0] = o.d;
      return true;
    }
    if (!(o.r instanceof String))
    {
      return false;
    }
    if (oStr2d((String)o.r, out))
    {
      return true;
    }
    return false;
  }

  /**
   * Converts a stack slot to number.  Returns true if the element at
   * the specified stack slot was converted to a number.  False
   * otherwise.  Note that this actually modifies the element stored at
   * <var>idx</var> in the stack (in faithful emulation of the PUC-Rio
   * code).  Corrupts <code>NUMOP[0]</code>.  Overloaded.
   * @param idx  absolute stack slot.
   */
  private boolean tonumber(int idx)
  {
    if (tonumber(stack[idx], NUMOP))
    {
      stack[idx].d = NUMOP[0];
      stack[idx].r = NUMBER;
      return true;
    }
    return false;
  }

  /**
   * Convert a pair of operands for an arithmetic opcode.  Stores
   * converted results in <code>out[0]</code> and <code>out[1]</code>.
   * @return true if and only if both values converted to number.
   */
  private static boolean toNumberPair(Slot x, Slot y, double[] out)
  {
    if (tonumber(y, out))
    {
      out[1] = out[0];
      if (tonumber(x, out))
      {
        return true;
      }
    }
    return false;
  }

  /**
   * Convert to string.  Returns true if element was number or string
   * (the number will have been converted to a string), false otherwise.
   * Note this actually modifies the element stored at <var>idx</var> in
   * the stack (in faithful emulation of the PUC-Rio code), and when it
   * returns <code>true</code>, <code>stack[idx].r instanceof String</code>
   * is true.
   */
  private boolean tostring(int idx)
  {
    // :todo: optimise
    Object o = objectAt(idx);
    String s = vmTostring(o);
    if (s == null)
    {
      return false;
    }
    stack[idx].r = s;
    return true;
  }

  /**
   * Equivalent to tryfuncTM from ldo.c.
   * @param func  absolute stack index of the function object.
   */
  private Object tryfuncTM(int func)
  {
    Object tm = tagmethod(stack[func].asObject(), "__call");
    if (!isFunction(tm))
    {
      gTypeerror(stack[func], "call");
    }
    stackInsertAt(tm, func);
    return tm;
  }

  /** Lua's is False predicate. */
  private boolean isFalse(Object o)
  {
    return o == NIL || o == Boolean.FALSE;
  }

  /** @deprecated DO NOT CALL. */
  private boolean isFalse(Slot o)
  {
    throw new IllegalArgumentException("isFalse called");
  }

  /** Make new CallInfo record. */
  private CallInfo inc_ci(int func, int baseArg, int top, int nresults)
  {
    CallInfo ci = new CallInfo(func, baseArg, top, nresults);
    civ.addElement(ci);
    return ci;
  }

  /** Pop topmost CallInfo record and return it. */
  private CallInfo dec_ci()
  {
    CallInfo ci = (CallInfo)civ.pop();
    return ci;
  }

  /** Equivalent to resume_error from ldo.c */
  private int resume_error(String msg)
  {
    stacksetsize(ci().base());
    stackAdd(msg);
    return ERRRUN;
  }

  /**
   * Return the stack element as an Object.  Converts double values into
   * Double objects.
   * @param idx  absolute index into stack (0 <= idx < stackSize).
   */
  private Object objectAt(int idx)
  {
    Object r = stack[idx].r;
    if (r != NUMBER)
    {
      return r;
    }
    return new Double(stack[idx].d);
  }

  /**
   * Sets the stack element.  Double instances are converted to double.
   * @param o  Object to store.
   * @param idx  absolute index into stack (0 <= idx < stackSize).
   */
  private void setObjectAt(Object o, int idx)
  {
    if (o instanceof Double)
    {
      stack[idx].r = NUMBER;
      stack[idx].d = ((Double)o).doubleValue();
      return;
    }
    stack[idx].r = o;
  }

  /**
   * Corresponds to ldump's luaU_dump method, but with data gone and writer
   * replaced by OutputStream.
   */
  static int uDump(Proto f, OutputStream writer, boolean strip)
      throws IOException
  {
    DumpState d = new DumpState(new DataOutputStream(writer), strip) ;
    d.DumpHeader();
    d.DumpFunction(f, null);
    d.writer.flush();
    return 0;   // Any errors result in thrown exceptions.
  }

}

final class DumpState
{
  DataOutputStream writer;
  boolean strip;

  DumpState(DataOutputStream writer, boolean strip)
  {
    this.writer = writer ;
    this.strip = strip ;
  }


  //////////////// dumper ////////////////////

  void DumpHeader() throws IOException
  {
    /*
     * In order to make the code more compact the dumper re-uses the
     * header defined in Loader.java.  It has to fix the endianness byte
     * first.
     */
    Loader.HEADER[6] = 0;
    writer.write(Loader.HEADER) ;
  }

  private void DumpInt(int i) throws IOException
  {
    writer.writeInt(i) ;        // big-endian
  }

  private void DumpNumber(double d) throws IOException
  {
    writer.writeDouble(d) ;     // big-endian
  }

  void DumpFunction(Proto f, String p) throws IOException
  {
    DumpString((f.source == p || strip) ? null : f.source);
    DumpInt(f.linedefined);
    DumpInt(f.lastlinedefined);
    writer.writeByte(f.nups);
    writer.writeByte(f.numparams);
    writer.writeBoolean(f.isVararg());
    writer.writeByte(f.maxstacksize);
    DumpCode(f);
    DumpConstants(f);
    DumpDebug(f);
  }

  private void DumpCode(Proto f) throws IOException
  {
    int n = f.sizecode ;
    int [] code = f.code ;
    DumpInt(n);
    for (int i = 0 ; i < n ; i++)
      DumpInt(code[i]) ;
  }

  private void DumpConstants(Proto f) throws IOException
  {
    int n = f.sizek;
    Slot[] k = f.k ;
    DumpInt(n) ;
    for (int i = 0 ; i < n ; i++)
    {
      Object o = k[i].r;
      if (o == Lua.NIL)
      {
        writer.writeByte(Lua.TNIL) ;
      }
      else if (o instanceof Boolean)
      {
        writer.writeByte(Lua.TBOOLEAN) ;
        writer.writeBoolean(((Boolean)o).booleanValue()) ;
      }
      else if (o == Lua.NUMBER)
      {
        writer.writeByte(Lua.TNUMBER) ;
        DumpNumber(k[i].d);
      }
      else if (o instanceof String)
      {
        writer.writeByte(Lua.TSTRING) ;
        DumpString((String)o) ;
      }
      else
      {
        //# assert false
      }
    }
    n = f.sizep ;
    DumpInt(n) ;
    for (int i = 0 ; i < n ; i++)
    {
      Proto subfunc = f.p[i] ;
      DumpFunction(subfunc, f.source) ;
    }
  }

  private void DumpString(String s) throws IOException
  {
    if (s == null)
    {
      DumpInt(0);
    }
    else
    {
      /*
       * Strings are dumped by converting to UTF-8 encoding.  The MIDP
       * 2.0 spec guarantees that this encoding will be supported (see
       * page 9 of midp-2_0-fr-spec.pdf).  Nonetheless, any
       * possible UnsupportedEncodingException is left to be thrown
       * (it's a subclass of IOException which is declared to be thrown).
       */
/*
 *       byte [] contents = s.getBytes("UTF-8") ;
      int size = contents.length ;
      DumpInt(size+1) ;
      writer.write(contents, 0, size) ;
      writer.writeByte(0) ;
*/
    }
  }

  private void DumpDebug(Proto f) throws IOException
  {
    if (strip)
    {
      DumpInt(0) ;
      DumpInt(0) ;
      DumpInt(0) ;
      return ;
    }

    int n = f.sizelineinfo;
    DumpInt(n);
    for (int i=0; i<n; i++)
      DumpInt(f.lineinfo[i]) ;

    n = f.sizelocvars;
    DumpInt(n);
    for (int i=0; i<n; i++)
    {
      LocVar locvar = f.locvars[i] ;
      DumpString(locvar.varname);
      DumpInt(locvar.startpc);
      DumpInt(locvar.endpc);
    }

    n = f.sizeupvalues;
    DumpInt(n);
    for (int i=0; i<n; i++)
      DumpString(f.upvalues[i]);
  }
}

final class Slot
{
  Object r;
  double d;

  Slot()
  {
  }

  Slot(Slot s)
  {
    this.r = s.r;
    this.d = s.d;
  }

  Slot(Object o)
  {
    this.setObject(o);
  }

  Object asObject()
  {
    if (r == Lua.NUMBER)
    {
      return new Double(d);
    }
    return r;
  }

  void setObject(Object o)
  {
    r = o;
    if (o instanceof Double)
    {
      r = Lua.NUMBER;
      d = ((Double)o).doubleValue();
    }
  }
}
