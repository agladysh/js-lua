/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/StringLib.java#1 $
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Vector;

/**
 * Contains Lua's string library.
 * The library can be opened using the {@link #open} method.
 */
public final class StringLib extends LuaJavaCallback
{
  // Each function in the string library corresponds to an instance of
  // this class which is associated (the 'which' member) with an integer
  // which is unique within this class.  They are taken from the following
  // set.
  private static final int BYTE = 1;
  private static final int CHAR = 2;
  private static final int DUMP = 3;
  private static final int FIND = 4;
  private static final int FORMAT = 5;
  private static final int GFIND = 6;
  private static final int GMATCH = 7;
  private static final int GSUB = 8;
  private static final int LEN = 9;
  private static final int LOWER = 10;
  private static final int MATCH = 11;
  private static final int REP = 12;
  private static final int REVERSE = 13;
  private static final int SUB = 14;
  private static final int UPPER = 15;

  private static final int GMATCH_AUX= 16;

  private static final StringLib GMATCH_AUX_FUN = new StringLib(GMATCH_AUX);

  /**
   * Which library function this object represents.  This value should
   * be one of the "enums" defined in the class.
   */
  private int which;

  /** Constructs instance, filling in the 'which' member. */
  private StringLib(int which)
  {
    this.which = which;
  }

  /**
   * Adjusts the output of string.format so that %e and %g use 'e'
   * instead of 'E' to indicate the exponent.  In other words so that
   * string.format follows the ISO C (ISO 9899) standard for printf.
   */
  public void formatISO()
  {
    FormatItem.E_LOWER = 'e';
  }

  /**
   * Implements all of the functions in the Lua string library.  Do not
   * call directly.
   * @param L  the Lua state in which to execute.
   * @return number of returned parameters, as per convention.
   */
  public int luaFunction(Lua L)
  {
    switch (which)
    {
      case BYTE:
        return byteFunction(L);
      case CHAR:
        return charFunction(L);
      case DUMP:
        return dump(L);
      case FIND:
        return find(L);
      case FORMAT:
        return format(L);
      case GMATCH:
        return gmatch(L);
      case GSUB:
        return gsub(L);
      case LEN:
        return len(L);
      case LOWER:
        return lower(L);
      case MATCH:
        return match(L);
      case REP:
        return rep(L);
      case REVERSE:
        return reverse(L);
      case SUB:
        return sub(L);
      case UPPER:
        return upper(L);
      case GMATCH_AUX:
        return gmatchaux(L);
    }
    return 0;
  }

  /**
   * Opens the string library into the given Lua state.  This registers
   * the symbols of the string library in a newly created table called
   * "string".
   * @param L  The Lua state into which to open.
   */
  public static void open(Lua L)
  {
    Object lib = L.register("string");

    r(L, "byte", BYTE);
    r(L, "char", CHAR);
    r(L, "dump", DUMP);
    r(L, "find", FIND);
    r(L, "format", FORMAT);
    r(L, "gfind", GFIND);
    r(L, "gmatch", GMATCH);
    r(L, "gsub", GSUB);
    r(L, "len", LEN);
    r(L, "lower", LOWER);
    r(L, "match", MATCH);
    r(L, "rep", REP);
    r(L, "reverse", REVERSE);
    r(L, "sub", SUB);
    r(L, "upper", UPPER);

    LuaTable mt = new LuaTable();
    L.setMetatable("", mt);     // set string metatable
    L.setField(mt, "__index", lib);
  }

  /** Register a function. */
  private static void r(Lua L, String name, int which)
  {
    StringLib f = new StringLib(which);
    Object lib = L.getGlobal("string");
    L.setField(lib, name, f);
  }

  /** Implements string.byte.  Name mangled to avoid keyword. */
  private static int byteFunction(Lua L)
  {
    String s = L.checkString(1);
    int posi = posrelat(L.optInt(2, 1), s);
    int pose = posrelat(L.optInt(3, posi), s);
    if (posi <= 0)
    {
      posi = 1;
    }
    if (pose > s.length())
    {
      pose = s.length();
    }
    if (posi > pose)
    {
      return 0; // empty interval; return no values
    }
    int n = pose - posi + 1;
    for (int i=0; i<n; ++i)
    {
      L.pushNumber(s.charAt(posi+i-1));
    }
    return n;
  }

  /** Implements string.char.  Name mangled to avoid keyword. */
  private static int charFunction(Lua L)
  {
    int n = L.getTop(); // number of arguments
    StringBuffer b = new StringBuffer();
    for (int i=1; i<=n; ++i)
    {
      int c = L.checkInt(i);
      L.argCheck((char)c == c, i, "invalid value");
      b.append((char)c);
    }
    L.push(b.toString());
    return 1;
  }

  /** Implements string.dump. */
  private static int dump(Lua L)
  {
    L.checkType(1, Lua.TFUNCTION);
    L.setTop(1);
    try
    {
      ByteArrayOutputStream s = new ByteArrayOutputStream();
      L.dump(L.value(1), s);
      byte[] a = s.toByteArray();
      s = null;
      StringBuffer b = new StringBuffer();
      for (int i=0; i<a.length; ++i)
      {
        b.append((char)(a[i]&0xff));
      }
      L.pushString(b.toString());
      return 1;
    }
    catch (IOException e_)
    {
      L.error("unabe to dump given function");
    }
    // NOTREACHED
    return 0;
  }

  /** Helper for find and match.  Equivalent to str_find_aux. */
  private static int findAux(Lua L, boolean isFind)
  {
    String s = L.checkString(1);
    String p = L.checkString(2);
    int l1 = s.length();
    int l2 = p.length();
    int init = posrelat(L.optInt(3, 1), s) - 1;
    if (init < 0)
    {
      init = 0;
    }
    else if (init > l1)
    {
      init = l1;
    }
    if (isFind && (L.toBoolean(L.value(4)) ||   // explicit request
        strpbrk(p, MatchState.SPECIALS) < 0)) // or no special characters?
    {   // do a plain search
      int off = lmemfind(s.substring(init), l1-init, p, l2);
      if (off >= 0)
      {
        L.pushNumber(init+off+1);
        L.pushNumber(init+off+l2);
        return 2;
      }
    }
    else
    {
      MatchState ms = new MatchState(L, s, l1);
      boolean anchor = p.charAt(0) == '^';
      int si = init;
      do
      {
        ms.level = 0;
        int res = ms.match(si, p, anchor ? 1 : 0);
        if (res >= 0)
        {
          if (isFind)
          {
            L.pushNumber(si + 1);       // start
            L.pushNumber(res);          // end
            return ms.push_captures(-1, -1) + 2;
          }     // else
          return ms.push_captures(si, res);
        }
      } while (si++ < ms.end && !anchor);
    }
    L.pushNil();        // not found
    return 1;
  }

  /** Implements string.find. */
  private static int find(Lua L)
  {
    return findAux(L, true);
  }

  /** Implement string.match.  Operates slightly differently from the
   * PUC-Rio code because instead of storing the iteration state as
   * upvalues of the C closure the iteration state is stored in an
   * Object[3] and kept on the stack.
   */
  private static int gmatch(Lua L)
  {
    Object[] state = new Object[3];
    state[0] = L.checkString(1);
    state[1] = L.checkString(2);
    state[2] = new Integer(0);
    L.push(GMATCH_AUX_FUN);
    L.push(state);
    return 2;
  }

  /**
   * Expects the iteration state, an Object[3] (see {@link
   * #gmatch}), to be first on the stack.
   */
  private static int gmatchaux(Lua L)
  {
    Object[] state = (Object[])L.value(1);
    String s = (String)state[0];
    String p = (String)state[1];
    int i = ((Integer)state[2]).intValue();
    MatchState ms = new MatchState(L, s, s.length());
    for ( ; i <= ms.end ; ++i)
    {
      ms.level = 0;
      int e = ms.match(i, p, 0);
      if (e >= 0)
      {
        int newstart = e;
        if (e == i)     // empty match?
          ++newstart;   // go at least one position
        state[2] = new Integer(newstart);
        return ms.push_captures(i, e);
      }
    }
    return 0;   // not found.
  }

  /** Implements string.gsub. */
  private static int gsub(Lua L)
  {
    String s = L.checkString(1);
    int sl = s.length();
    String p = L.checkString(2);
    int maxn = L.optInt(4, sl+1);
    boolean anchor = false;
    if (p.length() > 0)
    {
      anchor = p.charAt(0) == '^';
    }
    if (anchor)
      p = p.substring(1);
    MatchState ms = new MatchState(L, s, sl);
    StringBuffer b = new StringBuffer();

    int n = 0;
    int si = 0;
    while (n < maxn)
    {
      ms.level = 0;
      int e = ms.match(si, p, 0);
      if (e >= 0)
      {
        ++n;
        ms.addvalue(b, si, e);
      }
      if (e >= 0 && e > si)     // non empty match?
        si = e; // skip it
      else if (si < ms.end)
        b.append(s.charAt(si++));
      else
        break;
      if (anchor)
        break;
    }
    b.append(s.substring(si));
    L.pushString(b.toString());
    L.pushNumber(n);    // number of substitutions
    return 2;
  }

  static void addquoted(Lua L, StringBuffer b, int arg)
  {
    String s = L.checkString(arg);
    int l = s.length();
    b.append('"');
    for (int i=0; i<l; ++i)
    {
      switch (s.charAt(i))
      {
        case '"': case '\\': case '\n':
          b.append('\\');
          b.append(s.charAt(i));
          break;

        case '\r':
          b.append("\\r");
          break;

        case '\0':
          b.append("\\000");
          break;

        default:
          b.append(s.charAt(i));
          break;
      }
    }
    b.append('"');
  }

  static int format(Lua L)
  {
    int arg = 1;
    String strfrmt = L.checkString(1);
    int sfl = strfrmt.length();
    StringBuffer b = new StringBuffer();
    int i=0;
    while (i < sfl)
    {
      if (strfrmt.charAt(i) != MatchState.L_ESC)
      {
        b.append(strfrmt.charAt(i++));
      }
      else if (strfrmt.charAt(++i) == MatchState.L_ESC)
      {
        b.append(strfrmt.charAt(i++));
      }
      else      // format item
      {
        ++arg;
        FormatItem item = new FormatItem(L, strfrmt.substring(i));
        i += item.length();
        switch (item.type())
        {
          case 'c':
            item.formatChar(b, (char)L.checkNumber(arg));
            break;

          case 'd': case 'i':
          case 'o': case 'u': case 'x': case 'X':
          // :todo: should be unsigned conversions cope better with
          // negative number?
            item.formatInteger(b, (long)L.checkNumber(arg));
            break;

          case 'e': case 'E': case 'f':
          case 'g': case 'G':
            item.formatFloat(b, L.checkNumber(arg));
            break;

          case 'q':
            addquoted(L, b, arg);
            break;

          case 's':
            item.formatString(b, L.checkString(arg));
            break;

          default:
            return L.error("invalid option to 'format'");
        }
      }
    }
    L.pushString(b.toString());
    return 1;
  }

  /** Implements string.len. */
  private static int len(Lua L)
  {
    String s = L.checkString(1);
    L.pushNumber(s.length());
    return 1;
  }

  /** Implements string.lower. */
  private static int lower(Lua L)
  {
    String s = L.checkString(1);
    L.push(s.toLowerCase());
    return 1;
  }

  /** Implements string.match. */
  private static int match(Lua L)
  {
    return findAux(L, false);
  }

  /** Implements string.rep. */
  private static int rep(Lua L)
  {
    String s = L.checkString(1);
    int n = L.checkInt(2);
    StringBuffer b = new StringBuffer();
    for (int i=0; i<n; ++i)
    {
      b.append(s);
    }
    L.push(b.toString());
    return 1;
  }

  /** Implements string.reverse. */
  private static int reverse(Lua L)
  {
    String s = L.checkString(1);
    StringBuffer b = new StringBuffer();
    int l = s.length();
    while (--l >= 0)
    {
      b.append(s.charAt(l));
    }
    L.push(b.toString());
    return 1;
  }

  /** Helper for {@link #sub} and friends. */
  private static int posrelat(int pos, String s)
  {
    if (pos >= 0)
    {
      return pos;
    }
    int len = s.length();
    return len+pos+1;
  }

  /** Implements string.sub. */
  private static int sub(Lua L)
  {
    String s = L.checkString(1);
    int start = posrelat(L.checkInt(2), s);
    int end = posrelat(L.optInt(3, -1), s);
    if (start < 1)
    {
      start = 1;
    }
    if (end > s.length())
    {
      end = s.length();
    }
    if (start <= end)
    {
      L.push(s.substring(start-1, end));
    }
    else
    {
      L.pushLiteral("");
    }
    return 1;
  }

  /** Implements string.upper. */
  private static int upper(Lua L)
  {
    String s = L.checkString(1);
    L.push(s.toUpperCase());
    return 1;
  }

  /**
   * @return  character index of start of match (-1 if no match).
   */
  private static int lmemfind(String s1, int l1, String s2, int l2)
  {
    if (l2 == 0)
    {
      return 0; // empty strings are everywhere
    }
    else if (l2 > l1)
    {
      return -1;        // avoids a negative l1
    }
    return s1.indexOf(s2);
  }

  /**
   * Just like C's strpbrk.
   * @return an index into <var>s</var> or -1 for no match.
   */
  private static int strpbrk(String s, String set)
  {
    int l = set.length();
    for (int i=0; i<l; ++i)
    {
      int idx = s.indexOf(set.charAt(i));
      if (idx >= 0)
        return idx;
    }
    return -1;
  }
}

final class MatchState
{
  Lua L;
  /** The entire string that is the subject of the match. */
  String src;
  /** The subject's length. */
  int end;
  /** Total number of captures (finished or unfinished). */
  int level;
  /** Each capture element is a 2-element array of (index, len). */
  Vector capture = new Vector();
  // :todo: consider adding the pattern string as a member (and removing
  // p parameter from methods).

  // :todo: consider removing end parameter, if end always == // src.length()
  MatchState(Lua L, String src, int end)
  {
    this.L = L;
    this.src = src;
    this.end = end;
  }

  /**
   * Returns the length of capture <var>i</var>.
   */
  private int captureLen(int i)
  {
    int[] c = (int[])capture.elementAt(i);
    return c[1];
  }

  /**
   * Returns the init index of capture <var>i</var>.
   */
  private int captureInit(int i)
  {
    int[] c = (int[])capture.elementAt(i);
    return c[0];
  }

  /**
   * Returns the 2-element array for the capture <var>i</var>.
   */
  private int[] capture(int i)
  {
    return (int[])capture.elementAt(i);
  }

  int capInvalid()
  {
    return L.error("invalid capture index");
  }

  int malBra()
  {
    return L.error("malformed pattern (missing '[')");
  }

  int capUnfinished()
  {
    return L.error("unfinished capture");
  }

  int malEsc()
  {
    return L.error("malformed pattern (ends with '%')");
  }

  char check_capture(char l)
  {
    l -= '1';   // relies on wraparound.
    if (l >= level || captureLen(l) == CAP_UNFINISHED)
      capInvalid();
    return l;
  }

  int capture_to_close()
  {
    int lev = level;
    for (lev--; lev>=0; lev--)
      if (captureLen(lev) == CAP_UNFINISHED)
        return lev;
    return capInvalid();
  }

  int classend(String p, int pi)
  {
    switch (p.charAt(pi++))
    {
      case L_ESC:
        // assert pi < p.length() // checked by callers
        return pi+1;

      case '[':
        if (p.length() == pi)
          return malBra();
        if (p.charAt(pi) == '^')
          ++pi;
        do    // look for a ']'
        {
          if (p.length() == pi)
            return malBra();
          if (p.charAt(pi++) == L_ESC)
          {
            if (p.length() == pi)
              return malBra();
            ++pi;     // skip escapes (e.g. '%]')
            if (p.length() == pi)
              return malBra();
          }
        } while (p.charAt(pi) != ']');
        return pi+1;

      default:
        return pi;
    }
  }

  /**
   * @param c   char match.
   * @param cl  character class.
   */
  static boolean match_class(char c, char cl)
  {
    boolean res;
    switch (Character.toLowerCase(cl))
    {
      case 'a' : res = Syntax.isalpha(c); break;
      case 'c' : res = Syntax.iscntrl(c); break;
      case 'd' : res = Syntax.isdigit(c); break;
      case 'l' : res = Syntax.islower(c); break;
      case 'p' : res = Syntax.ispunct(c); break;
      case 's' : res = Syntax.isspace(c); break;
      case 'u' : res = Syntax.isupper(c); break;
      case 'w' : res = Syntax.isalnum(c); break;
      case 'x' : res = Syntax.isxdigit(c); break;
      case 'z' : res = (c == 0); break;
      default: return (cl == c);
    }
    return Character.isLowerCase(cl) ? res : !res;
  }

  /**
   * @param pi  index in p of start of class.
   * @param ec  index in p of end of class.
   */
  static boolean matchbracketclass(char c, String p, int pi, int ec)
  {
    // :todo: consider changing char c to int c, then -1 could be used
    // represent a guard value at the beginning and end of all strings (a
    // better NUL).  -1 of course would match no positive class.

    // assert p.charAt(pi) == '[';
    // assert p.charAt(ec) == ']';
    boolean sig = true;
    if (p.charAt(pi+1) == '^')
    {
      sig = false;
      ++pi;     // skip the '6'
    }
    while (++pi < ec)
    {
      if (p.charAt(pi) == L_ESC)
      {
        ++pi;
        if (match_class(c, p.charAt(pi)))
          return sig;
      }
      else if ((p.charAt(pi+1) == '-') && (pi+2 < ec))
      {
        pi += 2;
        if (p.charAt(pi-2) <= c && c <= p.charAt(pi))
          return sig;
      }
      else if (p.charAt(pi) == c)
      {
        return sig;
      }
    }
    return !sig;
  }

  static boolean singlematch(char c, String p, int pi, int ep)
  {
    switch (p.charAt(pi))
    {
      case '.': return true;    // matches any char
      case L_ESC: return match_class(c, p.charAt(pi+1));
      case '[': return matchbracketclass(c, p, pi, ep-1);
      default: return p.charAt(pi) == c;
    }
  }

  // Generally all the various match functions from PUC-Rio which take a
  // MatchState and return a "const char *" are transformed into
  // instance methods that take and return string indexes.

  int matchbalance(int si, String p, int pi)
  {
    if (pi+1 >= p.length())
      L.error("unbalanced pattern");
    if (si >= end || src.charAt(si) != p.charAt(pi))
    {
      return -1;
    }
    char b = p.charAt(pi);
    char e = p.charAt(pi+1);
    int cont = 1;
    while (++si < end)
    {
      if (src.charAt(si) == e)
      {
        if (--cont == 0)
          return si+1;
      }
      else if (src.charAt(si) == b)
      {
        ++cont;
      }
    }
    return -1;  // string ends out of balance
  }

  int max_expand(int si, String p, int pi, int ep)
  {
    int i = 0;  // counts maximum expand for item
    while (si+i < end && singlematch(src.charAt(si+i), p, pi, ep))
    {
      ++i;
    }
    // keeps trying to match with the maximum repetitions
    while (i >= 0)
    {
      int res = match(si+i, p, ep+1);
      if (res >= 0)
        return res;
      --i;      // else didn't match; reduce 1 repetition to try again
    }
    return -1;
  }

  int min_expand(int si, String p, int pi, int ep)
  {
    while (true)
    {
      int res = match(si, p, ep+1);
      if (res >= 0)
        return res;
      else if (si < end && singlematch(src.charAt(si), p, pi, ep))
        ++si;   // try with one more repetition
      else
        return -1;
    }
  }

  int start_capture(int si, String p, int pi, int what)
  {
    capture.setSize(level + 1);
    capture.setElementAt(new int[] { si, what }, level);
    ++level;
    int res = match(si, p, pi);
    if (res < 0)        // match failed
    {
      --level;
    }
    return res;
  }

  int end_capture(int si, String p, int pi)
  {
    int l = capture_to_close();
    capture(l)[1] = si - captureInit(l);        // close it
    int res = match(si, p, pi);
    if (res < 0)        // match failed?
    {
      capture(l)[1] = CAP_UNFINISHED;   // undo capture
    }
    return res;
  }

  int match_capture(int si, char l)
  {
    l = check_capture(l);
    int len = captureLen(l);
    if (end - si >= len &&
        src.regionMatches(false,
            captureInit(l),
            src,
            si,
            len))
    {
      return si+len;
    }
    return -1;
  }

  static final char L_ESC = '%';
  static final String SPECIALS = "^$*+?.([%-";
  private static final int CAP_UNFINISHED = -1;
  private static final int CAP_POSITION = -2;

  /**
   * @param si  index of subject at which to attempt match.
   * @param p   pattern string.
   * @param pi  index into pattern (from which to being matching).
   * @return the index of the end of the match, -1 for no match.
   */
  int match(int si, String p, int pi)
  {
    // This code has been considerably changed in the transformation
    // from C to Java.  There are the following non-obvious changes:
    // - The C code routinely relies on NUL being accessible at the end of
    //   the pattern string.  In Java we can't do this, so we use many
    //   more explicit length checks and pull error cases into this
    //   function.  :todo: consider appending NUL to the pattern string.
    // - The C code uses a "goto dflt" which is difficult to transform in
    //   the usual way.
init:   // labelled while loop emulates "goto init", which we use to
        // optimize tail recursion.
    while (true)
    {
      if (p.length() == pi)     // end of pattern
        return si;              // match succeeded
      switch (p.charAt(pi))
      {
        case '(':
          if (p.length() == pi + 1)
          {
            return capUnfinished();
          }
          if (p.charAt(pi+1) == ')')  // position capture?
            return start_capture(si, p, pi+2, CAP_POSITION);
          return start_capture(si, p, pi+1, CAP_UNFINISHED);

        case ')':       // end capture
          return end_capture(si, p, pi+1);

        case L_ESC:
          if (p.length() == pi + 1)
          {
            return malEsc();
          }
          switch (p.charAt(pi+1))
          {
            case 'b':   // balanced string?
              si = matchbalance(si, p, pi+2);
              if (si < 0)
                return si;
              pi += 4;
              // else return match(ms, s, p+4);
              continue init;    // goto init

            case 'f':   // frontier
              {
                pi += 2;
                if (p.length() == pi || p.charAt(pi) != '[')
                  return L.error("missing '[' after '%f' in pattern");
                int ep = classend(p, pi);   // indexes what is next
                char previous = (si == 0) ? '\0' : src.charAt(si-1);
                char at = (si == end) ? '\0' : src.charAt(si);
                if (matchbracketclass(previous, p, pi, ep-1) ||
                    !matchbracketclass(at, p, pi, ep-1))
                {
                  return -1;
                }
                pi = ep;
                // else return match(ms, s, ep);
              }
              continue init;    // goto init

            default:
              if (Syntax.isdigit(p.charAt(pi+1))) // capture results (%0-%09)?
              {
                si = match_capture(si, p.charAt(pi+1));
                if (si < 0)
                  return si;
                pi += 2;
                // else return match(ms, s, p+2);
                continue init;  // goto init
              }
              // We emulate a goto dflt by a fallthrough to the next
              // case (of the outer switch) and making sure that the
              // next case has no effect when we fallthrough to it from here.
              // goto dflt;
          }
          // FALLTHROUGH
        case '$':
          if (p.charAt(pi) == '$')
          {
            if (p.length() == pi+1)      // is the '$' the last char in pattern?
              return (si == end) ? si : -1;     // check end of string
            // else goto dflt;
          }
          // FALLTHROUGH
        default:        // it is a pattern item
          {
            int ep = classend(p, pi);   // indexes what is next
            boolean m = si < end && singlematch(src.charAt(si), p, pi, ep);
            if (p.length() > ep)
            {
              switch (p.charAt(ep))
              {
                case '?':       // optional
                  if (m)
                  {
                    int res = match(si+1, p, ep+1);
                    if (res >= 0)
                      return res;
                  }
                  pi = ep+1;
                  // else return match(s, ep+1);
                  continue init;      // goto init

                case '*':       // 0 or more repetitions
                  return max_expand(si, p, pi, ep);

                case '+':       // 1 or more repetitions
                  return m ? max_expand(si+1, p, pi, ep) : -1;

                case '-':       // 0 or more repetitions (minimum)
                  return min_expand(si, p, pi, ep);
              }
            }
            // else or default:
            if (!m)
              return -1;
            ++si;
            pi = ep;
            // return match(ms, s+1, ep);
            continue init;
          }
      }
    }
  }

  /**
   * @param s  index of start of match.
   * @param e  index of end of match.
   */
  Object onecapture(int i, int s, int e)
  {
    if (i >= level)
    {
      if (i == 0)       // level == 0, too
         return src.substring(s, e);    // add whole match
      else
        capInvalid();
        // NOTREACHED;
    }
    int l = captureLen(i);
    if (l == CAP_UNFINISHED)
      capUnfinished();
    if (l == CAP_POSITION)
      return L.valueOfNumber(captureInit(i) +1);
    return src.substring(captureInit(i), captureInit(i) + l);
  }

  void push_onecapture(int i, int s, int e)
  {
    L.push(onecapture(i, s, e));
  }

  /**
   * @param s  index of start of match.
   * @param e  index of end of match.
   */
  int push_captures(int s, int e)
  {
    int nlevels = (level == 0 && s >= 0) ? 1 : level;
    for (int i=0; i<nlevels; ++i)
      push_onecapture(i, s, e);
    return nlevels;     // number of strings pushed
  }

  /** A helper for gsub.  Equivalent to add_s from lstrlib.c. */
  void adds(StringBuffer b, int si, int ei)
  {
    String news = L.toString(L.value(3));
    int l = news.length();
    for (int i=0; i<l; ++i)
    {
      if (news.charAt(i) != L_ESC)
      {
        b.append(news.charAt(i));
      }
      else
      {
        ++i;    // skip L_ESC
        if (!Syntax.isdigit(news.charAt(i)))
        {
          b.append(news.charAt(i));
        }
        else if (news.charAt(i) == '0')
        {
          b.append(src.substring(si, ei));
        }
        else
        {
          // add capture to accumulated result
          b.append(L.toString(onecapture(news.charAt(i) - '1', si, ei)));
        }
      }
    }
  }

  /** A helper for gsub.  Equivalent to add_value from lstrlib.c. */
  void addvalue(StringBuffer b, int si, int ei)
  {
    switch (L.type(3))
    {
      case Lua.TNUMBER:
      case Lua.TSTRING:
        adds(b, si, ei);
        return;

      case Lua.TFUNCTION:
        {
          L.pushValue(3);
          int n = push_captures(si, ei);
          L.call(n, 1);
        }
        break;

      case Lua.TTABLE:
        L.push(L.getTable(L.value(3), onecapture(0, si, ei)));
        break;

      default:
      {
        L.argError(3, "string/function/table expected");
        return;
      }
    }
    if (!L.toBoolean(L.value(-1)))      // nil or false
    {
      L.pop(1);
      L.pushString(src.substring(si, ei));
    }
    else if (!L.isString(L.value(-1)))
    {
      L.error("invalid replacement value (a " +
          L.typeName(L.type(-1)) + ")");
    }
    b.append(L.toString(L.value(-1)));  // add result to accumulator
    L.pop(1);
  }
}

final class FormatItem
{
  private Lua L;
  private boolean left; // '-' flag
  private boolean sign; // '+' flag
  private boolean space;        // ' ' flag
  private boolean alt;  // '#' flag
  private boolean zero; // '0' flag
  private int width;    // minimum field width
  private int precision = -1;   // precision, -1 when no precision specified.
  private char type;    // the type of the conversion
  private int length;   // length of the format item in the format string.

  /**
   * Character used in formatted output when %e or %g format is used.
   */
  static char E_LOWER = 'E';
  /**
   * Character used in formatted output when %E or %G format is used.
   */
  static char E_UPPER = 'E';

  /**
   * Parse a format item (starting from after the <code>L_ESC</code>).
   * If you promise that there won't be any format errors, then
   * <var>L</var> can be <code>null</code>.
   */
  FormatItem(Lua L, String s)
  {
    this.L = L;
    int i=0;
    int l = s.length();
    // parse flags
flag:
    while (true)
    {
      if (i >=l )
        L.error("invalid format");
      switch (s.charAt(i))
      {
        case '-':
          left = true;
          break;
        case '+':
          sign = true;
          break;
        case ' ':
          space = true;
          break;
        case '#':
          alt = true;
          break;
        case '0':
          zero = true;
          break;
        default:
          break flag;
      }
      ++i;
    } /* flag */
    // parse width
    int widths = i;       // index of start of width specifier
    while (true)
    {
      if (i >= l)
        L.error("invalid format");
      if (Syntax.isdigit(s.charAt(i)))
        ++i;
      else
        break;
    }
    if (widths < i)
    {
      try
      {
        width = Integer.parseInt(s.substring(widths, i));
      }
      catch (NumberFormatException e_)
      {
      }
    }
    // parse precision
    if (s.charAt(i) == '.')
    {
      ++i;
      int precisions = i; // index of start of precision specifier
      while (true)
      {
        if (i >= l)
          L.error("invalid format");
        if (Syntax.isdigit(s.charAt(i)))
          ++i;
        else
          break;
      }
      if (precisions < i)
      {
        try
        {
          precision = Integer.parseInt(s.substring(precisions, i));
        }
        catch (NumberFormatException e_)
        {
        }
      }
    }
    switch (s.charAt(i))
    {
      case 'c':
      case 'd': case 'i':
      case 'o': case 'u': case 'x': case 'X':
      case 'e': case 'E': case 'f': case 'g': case 'G':
      case 'q':
      case 's':
        type = s.charAt(i);
        length = i+1;
        return;
    }
    L.error("invalid option to 'format'");
  }

  int length()
  {
    return length;
  }

  int type()
  {
    return type;
  }

  /**
   * Format the converted string according to width, and left.
   * zero padding is handled in either {@link FormatItem#formatInteger}
   * or {@link FormatItem#formatFloat}
   * (and width is fixed to 0 in such cases).  Therefore we can ignore
   * zero.
   */
  private void format(StringBuffer b, String s)
  {
    int l = s.length();
    if (l >= width)
    {
      b.append(s);
      return;
    }
    StringBuffer pad = new StringBuffer();
    while (l < width)
    {
      pad.append(' ');
      ++l;
    }
    if (left)
    {
      b.append(s);
      b.append(pad);
    }
    else
    {
      b.append(pad);
      b.append(s);
    }
  }

  // All the format* methods take a StringBuffer and append the
  // formatted representation of the value to it.
  // Sadly after a format* method has been invoked the object is left in
  // an unusable state and should not be used again.

  void formatChar(StringBuffer b, char c)
  {
    String s = String.valueOf(c);
    format(b, s);
  }

  void formatInteger(StringBuffer b, long i)
  {
    // :todo: improve inefficient use of implicit StringBuffer

    if (left)
      zero = false;
    if (precision >= 0)
      zero = false;

    int radix = 10;
    switch (type)
    {
      case 'o':
        radix = 8;
        break;
      case 'd': case 'i': case 'u':
        radix = 10;
        break;
      case 'x': case 'X':
        radix = 16;
        break;
      default:
        L.error("invalid format");
    }
    String s = Long.toString(i, radix);
    if (type == 'X')
      s = s.toUpperCase();
    if (precision == 0 && s.equals("0"))
      s = "";

    // form a prefix by strippping possible leading '-',
    // pad to precision,
    // add prefix,
    // pad to width.
    // extra wart: padding with '0' is implemented using precision
    // because this makes handling the prefix easier.
    String prefix = "";
    if (s.startsWith("-"))
    {
      prefix = "-";
      s = s.substring(1);
    }
    if (alt && radix == 16)
      prefix = "0x";
    if (prefix == "")
    {
      if (sign)
        prefix = "+";
      else if (space)
        prefix = " ";
    }
    if (alt && radix == 8 && !s.startsWith("0"))
      s = "0" + s;
    int l = s.length();
    if (zero)
    {
      precision = width - prefix.length();
      width = 0;
    }
    if (l < precision)
    {
      StringBuffer p = new StringBuffer();
      while (l < precision)
      {
        p.append('0');
        ++l;
      }
      p.append(s);
      s = p.toString();
    }
    s = prefix + s;
    format(b, s);
  }

  void formatFloat(StringBuffer b, double d)
  {
    switch (type)
    {
      case 'g': case 'G':
        formatFloatG(b, d);
        return;
      case 'f':
        formatFloatF(b, d);
        return;
      case 'e': case 'E':
        formatFloatE(b, d);
        return;
    }
  }

  private void formatFloatE(StringBuffer b, double d)
  {
    String s = formatFloatRawE(d);
    format(b, s);
  }

  /**
   * Returns the formatted string for the number without any padding
   * (which can be added by invoking {@link FormatItem#format} later).
   */
  private String formatFloatRawE(double d)
  {
    double m = Math.abs(d);
    int offset = 0;
    if (m >= 1e-3 && m < 1e7)
    {
      d *= 1e10;
      offset = 10;
    }

    String s = Double.toString(d);
    StringBuffer t = new StringBuffer(s);
    int e;      // Exponent value
    if (d == 0)
    {
      e = 0;
    }
    else
    {
      int ei = s.indexOf('E');
      e = Integer.parseInt(s.substring(ei+1));
      t.delete(ei, Integer.MAX_VALUE);
    }
    
    precisionTrim(t);

    e -= offset;
    if (Character.isLowerCase(type))
    {
      t.append(E_LOWER);
    }
    else
    {
      t.append(E_UPPER);
    }
    if (e >= 0)
    {
      t.append('+');
    }
    t.append(Integer.toString(e));

    zeroPad(t);
    return t.toString();
  }

  private void formatFloatF(StringBuffer b, double d)
  {
    String s = formatFloatRawF(d);
    format(b, s);
  }

  /**
   * Returns the formatted string for the number without any padding
   * (which can be added by invoking {@link FormatItem#format} later).
   */
  private String formatFloatRawF(double d)
  {
    String s = Double.toString(d);
    StringBuffer t = new StringBuffer(s);

    int di = s.indexOf('.');
    int ei = s.indexOf('E');
    if (ei >= 0)
    {
      t.delete(ei, Integer.MAX_VALUE);
      int e = Integer.parseInt(s.substring(ei+1));

      StringBuffer z = new StringBuffer();
      for (int i=0; i<Math.abs(e); ++i)
      {
        z.append('0');
      }

      if (e > 0)
      {
        t.deleteCharAt(di);
        t.append(z);
        t.insert(di+e, '.');
      }
      else
      {
        t.deleteCharAt(di);
        int at = t.charAt(0) == '-' ? 1 : 0;
        t.insert(at, z);
        t.insert(di, '.');
      }
    }

    precisionTrim(t);
    zeroPad(t);

    return t.toString();
  }

  private void formatFloatG(StringBuffer b, double d)
  {
    if (precision == 0)
    {
      precision = 1;
    }
    if (precision < 0)
    {
      precision = 6;
    }
    String s;
    // Decide whether to use %e or %f style.
    double m = Math.abs(d);
    if (m == 0)
    {
      // :todo: Could test for -0 and use "-0" appropriately.
      s = "0";
    }
    else if (m < 1e-4 || m >= Lua.iNumpow(10, precision))
    {
      // %e style
      --precision;
      s = formatFloatRawE(d);
      int di = s.indexOf('.');
      if (di >= 0)
      {
        // Trim trailing zeroes from fractional part
        int ei = s.indexOf('E');
        if (ei < 0)
        {
          ei = s.indexOf('e');
        }
        int i = ei-1;
        while (s.charAt(i) == '0')
        {
          --i;
        }
        if (s.charAt(i) != '.')
        {
          ++i;
        }
        StringBuffer a = new StringBuffer(s);
        a.delete(i, ei);
        s = a.toString();
      }
    }
    else
    {
      // %f style
      // For %g precision specifies the number of significant digits,
      // for %f precision specifies the number of fractional digits.
      // There is a problem because it's not obvious how many fractional
      // digits to format, it could be more than precision
      // (when .0001 <= m < 1) or it could be less than precision
      // (when m >= 1).
      // Instead of trying to work out the correct precision to use for
      // %f formatting we use a worse case to get at least all the
      // necessary digits, then we trim using string editing.  The worst
      // case is that 3 zeroes come after the decimal point before there
      // are any significant digits.
      // Save the required number of significant digits
      int required = precision;
      precision += 3;
      s = formatFloatRawF(d);
      int fsd = 0;      // First Significant Digit
      while (s.charAt(fsd) == '0' || s.charAt(fsd) == '.')
      {
        ++fsd;
      }
      // Note that all the digits to the left of the decimal point in
      // the formatted number are required digits (either significant
      // when m >= 1 or 0 when m < 1).  We know this because otherwise 
      // m >= (10**precision) and so formatting falls under the %e case.
      // That means that we can always trim the string at fsd+required
      // (this will remove the decimal point when m >=
      // (10**(precision-1)).
      StringBuffer a = new StringBuffer(s);
      a.delete(fsd+required, Integer.MAX_VALUE);
      if (s.indexOf('.') < a.length())
      {
        // Trim trailing zeroes
        int i = a.length() - 1;
        while (a.charAt(i) == '0')
        {
          a.deleteCharAt(i);
          --i;
        }
        if (a.charAt(i) == '.')
        {
          a.deleteCharAt(i);
        }
      }
      s = a.toString();
    }
    format(b, s);
  }

  void formatString(StringBuffer b, String s)
  {
    String p = s;

    if (precision >= 0 && precision < s.length())
    {
      p = s.substring(0, precision);
    }
    format(b, p);
  }

  private void precisionTrim(StringBuffer t)
  {
    if (precision < 0)
    {
      precision = 6;
    }

    String s = t.toString();
    int di = s.indexOf('.');
    int l = t.length();
    if (0 == precision)
    {
      t.delete(di, Integer.MAX_VALUE);
    }
    else if (l > di+precision)
    {
      t.delete(di+precision+1, Integer.MAX_VALUE);
    }
    else
    {
      for(; l <= di+precision; ++l)
      {
        t.append('0');
      }
    }
  }

  private void zeroPad(StringBuffer t)
  {
    if (zero && t.length() < width)
    {
      int at = t.charAt(0) == '-' ? 1 : 0;
      while (t.length() < width)
      {
        t.insert(at, '0');
      }
    }
  }
}
