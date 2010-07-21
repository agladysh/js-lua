/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/Syntax.java#1 $
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

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;


/**
 * Syntax analyser.  Lexing, parsing, code generation.
 */
final class Syntax
{
  /** End of File, must be -1 as that is what read() returns. */
  private static final int EOZ = -1;

  private static final int FIRST_RESERVED = 257;

  // WARNING: if you change the order of this enumeration,
  // grep "ORDER RESERVED"
  private static final int TK_AND       = FIRST_RESERVED + 0;
  private static final int TK_BREAK     = FIRST_RESERVED + 1;
  private static final int TK_DO        = FIRST_RESERVED + 2;
  private static final int TK_ELSE      = FIRST_RESERVED + 3;
  private static final int TK_ELSEIF    = FIRST_RESERVED + 4;
  private static final int TK_END       = FIRST_RESERVED + 5;
  private static final int TK_FALSE     = FIRST_RESERVED + 6;
  private static final int TK_FOR       = FIRST_RESERVED + 7;
  private static final int TK_FUNCTION  = FIRST_RESERVED + 8;
  private static final int TK_IF        = FIRST_RESERVED + 9;
  private static final int TK_IN        = FIRST_RESERVED + 10;
  private static final int TK_LOCAL     = FIRST_RESERVED + 11;
  private static final int TK_NIL       = FIRST_RESERVED + 12;
  private static final int TK_NOT       = FIRST_RESERVED + 13;
  private static final int TK_OR        = FIRST_RESERVED + 14;
  private static final int TK_REPEAT    = FIRST_RESERVED + 15;
  private static final int TK_RETURN    = FIRST_RESERVED + 16;
  private static final int TK_THEN      = FIRST_RESERVED + 17;
  private static final int TK_TRUE      = FIRST_RESERVED + 18;
  private static final int TK_UNTIL     = FIRST_RESERVED + 19;
  private static final int TK_WHILE     = FIRST_RESERVED + 20;
  private static final int TK_CONCAT    = FIRST_RESERVED + 21;
  private static final int TK_DOTS      = FIRST_RESERVED + 22;
  private static final int TK_EQ        = FIRST_RESERVED + 23;
  private static final int TK_GE        = FIRST_RESERVED + 24;
  private static final int TK_LE        = FIRST_RESERVED + 25;
  private static final int TK_NE        = FIRST_RESERVED + 26;
  private static final int TK_NUMBER    = FIRST_RESERVED + 27;
  private static final int TK_NAME      = FIRST_RESERVED + 28;
  private static final int TK_STRING    = FIRST_RESERVED + 29;
  private static final int TK_EOS       = FIRST_RESERVED + 30;

  private static final int NUM_RESERVED = TK_WHILE - FIRST_RESERVED + 1;

  /** Equivalent to luaX_tokens.  ORDER RESERVED */
  static String[] tokens = new String[]
  {
    "and", "break", "do", "else", "elseif",
    "end", "false", "for", "function", "if",
    "in", "local", "nil", "not", "or", "repeat",
    "return", "then", "true", "until", "while",
    "..", "...", "==", ">=", "<=", "~=",
    "<number>", "<name>", "<string>", "<eof>"
  };

  static HashMap reserved = new HashMap();
  static
  {
    for (int i=0; i < NUM_RESERVED; ++i)
    {
      reserved.put(tokens[i], new Integer(FIRST_RESERVED+i));
    }
  }

  // From struct LexState

  /** current character */
  int current;
  /** input line counter */
  int linenumber = 1;
  /** line of last token 'consumed' */
  int lastline = 1;
  /**
   * The token value.  For "punctuation" tokens this is the ASCII value
   * for the character for the token; for other tokens a member of the
   * enum (all of which are > 255).
   */
  int token;
  /** Semantic info for token; a number. */
  double tokenR;
  /** Semantic info for token; a string. */
  String tokenS;

  /** Lookahead token value. */
  int lookahead = TK_EOS;
  /** Semantic info for lookahead; a number. */
  double lookaheadR;
  /** Semantic info for lookahead; a string. */
  String lookaheadS;

  /** Semantic info for return value from {@link #llex}; a number. */
  double semR;
  /** As {@link #semR}, for string. */
  String semS;

  /** FuncState for current (innermost) function being parsed. */
  FuncState fs;
  Lua L;

  /** input stream */
  private Reader z;

  /** Buffer for tokens. */
  StringBuffer buff = new StringBuffer();

  /** current source name */
  String source;

  /** locale decimal point. */
  private char decpoint = '.';

  private Syntax(Lua L, Reader z, String source) throws IOException
  {
    this.L = L;
    this.z = z;
    this.source = source;
    next();
  }

  int lastline()
  {
    return lastline;
  }


  // From <ctype.h>

  // Implementations of functions from <ctype.h> are only correct copies
  // to the extent that Lua requires them.
  // Generally they have default access so that StringLib can see them.
  // Unlike C's these version are not locale dependent, they use the
  // ISO-Latin-1 definitions from CLDC 1.1 Character class.

  static boolean isalnum(int c)
  {
    char ch = (char)c;
    return Character.isUpperCase(ch) ||
        Character.isLowerCase(ch) ||
        Character.isDigit(ch);
  }

  static boolean isalpha(int c)
  {
    char ch = (char)c;
    return Character.isUpperCase(ch) ||
        Character.isLowerCase(ch);
  }

  /** True if and only if the char (when converted from the int) is a
   * control character.
   */
  static boolean iscntrl(int c)
  {
    return (char)c < 0x20 || c == 0x7f;
  }

  static boolean isdigit(int c)
  {
    return Character.isDigit((char)c);
  }

  static boolean islower(int c)
  {
    return Character.isLowerCase((char)c);
  }

  /**
   * A character is punctuation if not cntrl, not alnum, and not space.
   */
  static boolean ispunct(int c)
  {
    return !isalnum(c) && !iscntrl(c) && !isspace(c);
  }

  static boolean isspace(int c)
  {
    return c == ' ' ||
           c == '\f' ||
           c == '\n' ||
           c == '\r' ||
           c == '\t';
  }

  static boolean isupper(int c)
  {
    return Character.isUpperCase((char)c);
  }

  static boolean isxdigit(int c)
  {
    return Character.isDigit((char)c) ||
      ('a' <= c && c <= 'f') ||
      ('A' <= c && c <= 'F');
  }

  // From llex.c

  private boolean check_next(String set) throws IOException
  {
    if (set.indexOf(current) < 0)
    {
      return false;
    }
    save_and_next();
    return true;
  }

  private boolean currIsNewline()
  {
    return current == '\n' || current == '\r';
  }

  private void inclinenumber() throws IOException
  {
    int old = current;
    //# assert currIsNewline()
    next();     // skip '\n' or '\r'
    if (currIsNewline() && current != old)
    {
      next();   // skip '\n\r' or '\r\n'
    }
    if (++linenumber < 0)       // overflow
    {
      xSyntaxerror("chunk has too many lines");
    }
  }

  private int skip_sep() throws IOException
  {
    int count = 0;
    int s = current;
    //# assert s == '[' || s == ']'
    save_and_next();
    while (current == '=')
    {
      save_and_next();
      count++;
    }
    return (current == s) ? count : (-count) - 1;
  }

  private void read_long_string(boolean isString, int sep) throws IOException
  {
    int cont = 0;
    save_and_next();  /* skip 2nd `[' */
    if (currIsNewline())  /* string starts with a newline? */
      inclinenumber();  /* skip it */
loop:
    while (true)
    {
      switch (current)
      {
        case EOZ:
          xLexerror(isString ? "unfinished long string" :
                                "unfinished long comment",
              TK_EOS);
          break;  /* to avoid warnings */
        case ']':
          if (skip_sep() == sep)
          {
            save_and_next();  /* skip 2nd `]' */
            break loop;
          }
          break;

        case '\n':
        case '\r':
          save('\n');
          inclinenumber();
          if (!isString)
            buff.setLength(0) ; /* avoid wasting space */
          break;

        default:
          if (isString) save_and_next();
          else next();
      }
    } /* loop */
    if (isString)
    {
      String rawtoken = buff.toString();
      int trim_by = 2+sep ;
      semS = rawtoken.substring(trim_by, rawtoken.length()-trim_by) ;
    }
  }


  /** Lex a token and return it.  The semantic info for the token is
   * stored in <code>this.semR</code> or <code>this.semS</code> as
   * appropriate.
   */
  private int llex() throws IOException
  {
    buff.setLength(0);
    while (true)
    {
      switch (current)
      {
        case '\n':
        case '\r':
          inclinenumber();
          continue;
        case '-':
          next();
          if (current != '-')
            return '-';
          /* else is a comment */
          next();
          if (current == '[')
          {
            int sep = skip_sep();
            buff.setLength(0) ; /* `skip_sep' may dirty the buffer */
            if (sep >= 0)
            {
              read_long_string(false, sep);  /* long comment */
              buff.setLength(0) ;
              continue;
            }
          }
          /* else short comment */
          while (!currIsNewline() && current != EOZ)
            next();
          continue;

        case '[':
          int sep = skip_sep();
          if (sep >= 0)
          {
            read_long_string(true, sep);
            return TK_STRING;
          }
          else if (sep == -1)
            return '[';
          else
            xLexerror("invalid long string delimiter", TK_STRING);
          continue;     // avoids Checkstyle warning.

        case '=':
          next() ;
          if (current != '=')
          { return '=' ; }
          else
          {
            next() ;
            return TK_EQ ;
          }
        case '<':
          next() ;
          if (current != '=')
          { return '<' ; }
          else
          {
            next() ;
            return TK_LE ;
          }
        case '>':
          next() ;
          if (current != '=')
          { return '>' ; }
          else
          {
            next() ;
            return TK_GE ;
          }
        case '~':
          next();
          if (current != '=')
          { return '~'; }
          else
          {
            next();
            return TK_NE;
          }
        case '"':
        case '\'':
          read_string(current);
          return TK_STRING;
        case '.':
          save_and_next();
          if (check_next("."))
          {
            if (check_next("."))
            {
              return TK_DOTS;
            }
            else
            {
              return TK_CONCAT ;
            }
          }
          else if (!isdigit(current))
          {
            return '.';
          }
          else
          {
            read_numeral();
            return TK_NUMBER;
          }
        case EOZ:
          return TK_EOS;
        default:
          if (isspace(current))
          {
            // assert !currIsNewline();
            next();
            continue;
          }
          else if (isdigit(current))
          {
            read_numeral();
            return TK_NUMBER;
          }
          else if (isalpha(current) || current == '_')
          {
            // identifier or reserved word
            do
            {
              save_and_next();
            } while (isalnum(current) || current == '_');
            String s = buff.toString();
            Object t = reserved.get(s);
            if (t == null)
            {
              semS = s;
              return TK_NAME;
            }
            else
            {
              return ((Integer)t).intValue();
            }
          }
          else
          {
            int c = current;
            next();
            return c; // single-char tokens
          }
      }
    }
  }

  private void next() throws IOException
  {
    current = z.read();
  }

  /** Reads number.  Writes to semR. */
  private void read_numeral() throws IOException
  {
    // assert isdigit(current);
    do
    {
      save_and_next();
    } while (isdigit(current) || current == '.');
    if (check_next("Ee"))       // 'E' ?
    {
      check_next("+-"); // optional exponent sign
    }
    while (isalnum(current) || current == '_')
    {
      save_and_next();
    }
    // :todo: consider doing PUC-Rio's decimal point tricks.
    try
    {
      semR = Double.parseDouble(buff.toString());
      return;
    }
    catch (NumberFormatException e)
    {
      xLexerror("malformed number", TK_NUMBER);
    }
  }

  /** Reads string.  Writes to semS. */
  private void read_string(int del) throws IOException
  {
    save_and_next();
    while (current != del)
    {
      switch (current)
      {
        case EOZ:
          xLexerror("unfinished string", TK_EOS);
          continue;     // avoid compiler warning
        case '\n':
        case '\r':
          xLexerror("unfinished string", TK_STRING);
          continue;     // avoid compiler warning
        case '\\':
        {
          int c;
          next();       // do not save the '\'
          switch (current)
          {
            case 'a': c = 7; break;     // no '\a' in Java.
            case 'b': c = '\b'; break;
            case 'f': c = '\f'; break;
            case 'n': c = '\n'; break;
            case 'r': c = '\r'; break;
            case 't': c = '\t'; break;
            case 'v': c = 11; break;    // no '\v' in Java.
            case '\n': case '\r':
              save('\n');
              inclinenumber();
              continue;
            case EOZ:
              continue; // will raise an error next loop
            default:
              if (!isdigit(current))
              {
                save_and_next();        // handles \\, \", \', \?
              }
              else    // \xxx
              {
                int i = 0;
                c = 0;
                do
                {
                  c = 10*c + (current - '0');
                  next();
                } while (++i<3 && isdigit(current));
                // In unicode, there are no bounds on a 3-digit decimal.
                save(c);
              }
              continue;
          }
          save(c);
          next();
          continue;
        }
        default:
          save_and_next();
      }
    }
    save_and_next();    // skip delimiter
    String rawtoken = buff.toString() ;
    semS = rawtoken.substring(1, rawtoken.length()-1) ;
  }

  private void save()
  {
    buff.append((char)current);
  }

  private void save(int c)
  {
    buff.append((char)c);
  }

  private void save_and_next() throws IOException
  {
    save();
    next();
  }

  /** Getter for source. */
  String source()
  {
    return source;
  }

  private String txtToken(int tok)
  {
    switch (tok)
    {
      case TK_NAME:
      case TK_STRING:
      case TK_NUMBER:
        return buff.toString();
      default:
        return xToken2str(tok);
    }
  }

  /** Equivalent to <code>luaX_lexerror</code>. */
  private void xLexerror(String msg, int tok)
  {
    msg = source + ":" + linenumber + ": " + msg;
    if (tok != 0)
    {
      msg = msg + " near '" + txtToken(tok) + "'";
    }
    L.pushString(msg);
    L.dThrow(Lua.ERRSYNTAX);
  }

  /** Equivalent to <code>luaX_next</code>. */
  private void xNext() throws IOException
  {
    lastline = linenumber;
    if (lookahead != TK_EOS)    // is there a look-ahead token?
    {
      token = lookahead;        // Use this one,
      tokenR = lookaheadR;
      tokenS = lookaheadS;
      lookahead = TK_EOS;       // and discharge it.
    }
    else
    {
      token = llex();
      tokenR = semR;
      tokenS = semS;
    }
  }

  /** Equivalent to <code>luaX_syntaxerror</code>. */
  void xSyntaxerror(String msg)
  {
    xLexerror(msg, token);
  }

  private static String xToken2str(int token)
  {
    if (token < FIRST_RESERVED)
    {
      // assert token == (char)token;
      if (iscntrl(token))
      {
        return "char(" + token + ")";
      }
      return (new Character((char)token)).toString();
    }
    return tokens[token-FIRST_RESERVED];
  }

  // From lparser.c

  private static boolean block_follow(int token)
  {
    switch (token)
    {
      case TK_ELSE: case TK_ELSEIF: case TK_END:
      case TK_UNTIL: case TK_EOS:
        return true;
      default:
        return false;
    }
  }

  private void check(int c)
  {
    if (token != c)
    {
      error_expected(c);
    }
  }

  /**
   * @param what   the token that is intended to end the match.
   * @param who    the token that begins the match.
   * @param where  the line number of <var>what</var>.
   */
  private void check_match(int what, int who, int where)
      throws IOException
  {
    if (!testnext(what))
    {
      if (where == linenumber)
      {
        error_expected(what);
      }
      else
      {
        xSyntaxerror("'" + xToken2str(what) + "' expected (to close '" +
            xToken2str(who) + "' at line " + where + ")");
      }
    }
  }

  private void close_func()
  {
    removevars(0);
    fs.kRet(0, 0);  // final return;
    fs.close();
    // :todo: check this is a valid assertion to make
    //# assert fs != fs.prev
    fs = fs.prev;
  }


    static String opcode_name(int op)
    {
      switch (op)
      {
      case Lua.OP_MOVE: return "MOVE";
      case Lua.OP_LOADK: return "LOADK";
      case Lua.OP_LOADBOOL: return "LOADBOOL";
      case Lua.OP_LOADNIL: return "LOADNIL";
      case Lua.OP_GETUPVAL: return "GETUPVAL";
      case Lua.OP_GETGLOBAL: return "GETGLOBAL";
      case Lua.OP_GETTABLE: return "GETTABLE";
      case Lua.OP_SETGLOBAL: return "SETGLOBAL";
      case Lua.OP_SETUPVAL: return "SETUPVAL";
      case Lua.OP_SETTABLE: return "SETTABLE";
      case Lua.OP_NEWTABLE: return "NEWTABLE";
      case Lua.OP_SELF: return "SELF";
      case Lua.OP_ADD: return "ADD";
      case Lua.OP_SUB: return "SUB";
      case Lua.OP_MUL: return "MUL";
      case Lua.OP_DIV: return "DIV";
      case Lua.OP_MOD: return "MOD";
      case Lua.OP_POW: return "POW";
      case Lua.OP_UNM: return "UNM";
      case Lua.OP_NOT: return "NOT";
      case Lua.OP_LEN: return "LEN";
      case Lua.OP_CONCAT: return "CONCAT";
      case Lua.OP_JMP: return "JMP";
      case Lua.OP_EQ: return "EQ";
      case Lua.OP_LT: return "LT";
      case Lua.OP_LE: return "LE";
      case Lua.OP_TEST: return "TEST";
      case Lua.OP_TESTSET: return "TESTSET";
      case Lua.OP_CALL: return "CALL";
      case Lua.OP_TAILCALL: return "TAILCALL";
      case Lua.OP_RETURN: return "RETURN";
      case Lua.OP_FORLOOP: return "FORLOOP";
      case Lua.OP_FORPREP: return "FORPREP";
      case Lua.OP_TFORLOOP: return "TFORLOOP";
      case Lua.OP_SETLIST: return "SETLIST";
      case Lua.OP_CLOSE: return "CLOSE";
      case Lua.OP_CLOSURE: return "CLOSURE";
      case Lua.OP_VARARG: return "VARARG";
      default: return "??"+op;
      }
    }

  private void codestring(Expdesc e, String s)
  {
    e.init(Expdesc.VK, fs.kStringK(s));
  }

  private void checkname(Expdesc e) throws IOException
  {
    codestring(e, str_checkname());
  }

  private void enterlevel()
  {
    L.nCcalls++ ;
  }

  private void error_expected(int tok)
  {
    xSyntaxerror("'" + xToken2str(tok) + "' expected");
  }

  private void leavelevel()
  {
    L.nCcalls-- ;
  }


  /** Equivalent to luaY_parser. */
  static Proto parser(Lua L, Reader in, String name)
      throws IOException
  {
    Syntax ls = new Syntax(L, in, name);
    FuncState fs = new FuncState(ls);
    ls.open_func(fs);
    fs.f.setIsVararg();
    ls.xNext();
    ls.chunk();
    ls.check(TK_EOS);
    ls.close_func();
    //# assert fs.prev == null
    //# assert fs.f.nups == 0
    //# assert ls.fs == null
    return fs.f;
  }

  private void removevars(int tolevel)
  {
    // :todo: consider making a method in FuncState.
    while (fs.nactvar > tolevel)
    {
      fs.getlocvar(--fs.nactvar).endpc = fs.pc;
    }
  }

  private void singlevar(Expdesc var) throws IOException
  {
    String varname = str_checkname();
    if (singlevaraux(fs, varname, var, true) == Expdesc.VGLOBAL)
    {
      var.setInfo(fs.kStringK(varname));
    }
  }

  private int singlevaraux(FuncState f,
      String n,
      Expdesc var,
      boolean base)
  {
    if (f == null)      // no more levels?
    {
      var.init(Expdesc.VGLOBAL, Lua.NO_REG);    // default is global variable
      return Expdesc.VGLOBAL;
    }
    else
    {
      int v = f.searchvar(n);
      if (v >= 0)
      {
        var.init(Expdesc.VLOCAL, v);
        if (!base)
        {
          f.markupval(v);       // local will be used as an upval
        }
        return Expdesc.VLOCAL;
      }
      else    // not found at current level; try upper one
      {
        if (singlevaraux(f.prev, n, var, false) == Expdesc.VGLOBAL)
        {
          return Expdesc.VGLOBAL;
        }
        var.upval(indexupvalue(f, n, var));     // else was LOCAL or UPVAL
        return Expdesc.VUPVAL;
      }
    }
  }

  private String str_checkname() throws IOException
  {
    check(TK_NAME);
    String s = tokenS;
    xNext();
    return s;
  }

  private boolean testnext(int c) throws IOException
  {
    if (token == c)
    {
      xNext();
      return true;
    }
    return false;
  }


  // GRAMMAR RULES

  private void chunk() throws IOException
  {
    // chunk -> { stat [';'] }
    boolean islast = false;
    enterlevel();
    while (!islast && !block_follow(token))
    {
      islast = statement();
      testnext(';');
      //# assert fs.f.maxstacksize >= fs.freereg && fs.freereg >= fs.nactvar
      fs.freereg = fs.nactvar;
    }
    leavelevel();
  }

  private void constructor(Expdesc t) throws IOException
  {
    // constructor -> ??
    int line = linenumber;
    int pc = fs.kCodeABC(Lua.OP_NEWTABLE, 0, 0, 0);
    ConsControl cc = new ConsControl(t) ;
    t.init(Expdesc.VRELOCABLE, pc);
    cc.v.init(Expdesc.VVOID, 0);        /* no value (yet) */
    fs.kExp2nextreg(t);  /* fix it at stack top (for gc) */
    checknext('{');
    do
    {
      //# assert cc.v.k == Expdesc.VVOID || cc.tostore > 0
      if (token == '}')
        break;
      closelistfield(cc);
      switch(token)
      {
        case TK_NAME:  /* may be listfields or recfields */
          xLookahead();
          if (lookahead != '=')  /* expression? */
            listfield(cc);
          else
            recfield(cc);
          break;

        case '[':  /* constructor_item -> recfield */
        recfield(cc);
        break;

        default:  /* constructor_part -> listfield */
          listfield(cc);
          break;
      }
    } while (testnext(',') || testnext(';'));
    check_match('}', '{', line);
    lastlistfield(cc);
    int [] code = fs.f.code ;
    code[pc] = Lua.SETARG_B(code[pc], oInt2fb(cc.na)); /* set initial array size */
    code[pc] = Lua.SETARG_C(code[pc], oInt2fb(cc.nh)); /* set initial table size */
  }

  private static int oInt2fb(int x)
  {
    int e = 0;  /* exponent */
    while (x < 0 || x >= 16)
    {
      x = (x+1) >>> 1;
      e++;
    }
    return (x < 8) ? x : (((e+1) << 3) | (x - 8));
  }

  private void recfield(ConsControl cc) throws IOException
  {
    /* recfield -> (NAME | `['exp1`]') = exp1 */
    int reg = fs.freereg;
    Expdesc key = new Expdesc() ;
    Expdesc val = new Expdesc() ;
    if (token == TK_NAME)
    {
      // yChecklimit(fs, cc.nh, MAX_INT, "items in a constructor");
      checkname(key);
    }
    else  /* token == '[' */
      yindex(key);
    cc.nh++;
    checknext('=');
    fs.kExp2RK(key);
    expr(val);
    fs.kCodeABC(Lua.OP_SETTABLE, cc.t.info, fs.kExp2RK(key), fs.kExp2RK(val));
    fs.freereg = reg;  /* free registers */
  }

  private void lastlistfield(ConsControl cc)
  {
    if (cc.tostore == 0)
      return;
    if (hasmultret(cc.v.k))
    {
      fs.kSetmultret(cc.v);
      fs.kSetlist(cc.t.info, cc.na, Lua.MULTRET);
      cc.na--;  /* do not count last expression (unknown number of elements) */
    }
    else
    {
      if (cc.v.k != Expdesc.VVOID)
        fs.kExp2nextreg(cc.v);
      fs.kSetlist(cc.t.info, cc.na, cc.tostore);
    }
  }

  private void closelistfield(ConsControl cc)
  {
    if (cc.v.k == Expdesc.VVOID)
      return;  /* there is no list item */
    fs.kExp2nextreg(cc.v);
    cc.v.k = Expdesc.VVOID;
    if (cc.tostore == Lua.LFIELDS_PER_FLUSH)
    {
      fs.kSetlist(cc.t.info, cc.na, cc.tostore);  /* flush */
      cc.tostore = 0;  /* no more items pending */
    }
  }

  private void expr(Expdesc v) throws IOException
  {
    subexpr(v, 0);
  }

  /** @return number of expressions in expression list. */
  private int explist1(Expdesc v) throws IOException
  {
    // explist1 -> expr { ',' expr }
    int n = 1;  // at least one expression
    expr(v);
    while (testnext(','))
    {
      fs.kExp2nextreg(v);
      expr(v);
      ++n;
    }
    return n;
  }

  private void exprstat() throws IOException
  {
    // stat -> func | assignment
    LHSAssign v = new LHSAssign() ;
    primaryexp(v.v);
    if (v.v.k == Expdesc.VCALL)      // stat -> func
    {
      fs.setargc(v.v, 1); // call statement uses no results
    }
    else      // stat -> assignment
    {
      v.prev = null;
      assignment(v, 1);
    }
  }

  /*
  ** check whether, in an assignment to a local variable, the local variable
  ** is needed in a previous assignment (to a table). If so, save original
  ** local value in a safe place and use this safe copy in the previous
  ** assignment.
  */
  private void check_conflict(LHSAssign lh, Expdesc v)
  {
    int extra = fs.freereg;  /* eventual position to save local variable */
    boolean conflict = false ;
    for (; lh != null; lh = lh.prev)
    {
      if (lh.v.k == Expdesc.VINDEXED)
      {
        if (lh.v.info == v.info)    /* conflict? */
        {
          conflict = true;
          lh.v.info = extra;  /* previous assignment will use safe copy */
        }
        if (lh.v.aux == v.info)    /* conflict? */
        {
          conflict = true;
          lh.v.aux = extra;  /* previous assignment will use safe copy */
        }
      }
    }
    if (conflict)
    {
      fs.kCodeABC(Lua.OP_MOVE, fs.freereg, v.info, 0);  /* make copy */
      fs.kReserveregs(1);
    }
  }

  private void assignment(LHSAssign lh, int nvars) throws IOException
  {
    Expdesc e = new Expdesc() ;
    int kind = lh.v.k ;
    if (!(Expdesc.VLOCAL <= kind && kind <= Expdesc.VINDEXED))
      xSyntaxerror("syntax error");
    if (testnext(','))    /* assignment -> `,' primaryexp assignment */
    {
      LHSAssign nv = new LHSAssign(lh) ;
      primaryexp(nv.v);
      if (nv.v.k == Expdesc.VLOCAL)
        check_conflict(lh, nv.v);
      assignment(nv, nvars+1);
    }
    else    /* assignment -> `=' explist1 */
    {
      int nexps;
      checknext('=');
      nexps = explist1(e);
      if (nexps != nvars)
      {
        adjust_assign(nvars, nexps, e);
        if (nexps > nvars)
          fs.freereg -= nexps - nvars;  /* remove extra values */
      }
      else
      {
        fs.kSetoneret(e);  /* close last expression */
        fs.kStorevar(lh.v, e);
        return;  /* avoid default */
      }
    }
    e.init(Expdesc.VNONRELOC, fs.freereg-1);    /* default assignment */
    fs.kStorevar(lh.v, e);
  }


  private void funcargs(Expdesc f) throws IOException
  {
    Expdesc args = new Expdesc();
    int line = linenumber;
    switch (token)
    {
      case '(':         // funcargs -> '(' [ explist1 ] ')'
        if (line != lastline)
        {
          xSyntaxerror("ambiguous syntax (function call x new statement)");
        }
        xNext();
        if (token == ')')       // arg list is empty?
        {
          args.setKind(Expdesc.VVOID);
        }
        else
        {
          explist1(args);
          fs.kSetmultret(args);
        }
        check_match(')', '(', line);
        break;

      case '{':         // funcargs -> constructor
        constructor(args);
        break;

      case TK_STRING:   // funcargs -> STRING
        codestring(args, tokenS);
        xNext();        // must use tokenS before 'next'
        break;

      default:
        xSyntaxerror("function arguments expected");
        return;
    }
    // assert (f.kind() == VNONRELOC);
    int nparams;
    int base = f.info();        // base register for call
    if (args.hasmultret())
    {
      nparams = Lua.MULTRET;     // open call
    }
    else
    {
      if (args.kind() != Expdesc.VVOID)
      {
        fs.kExp2nextreg(args);  // close last argument
      }
      nparams = fs.freereg - (base+1);
    }
    f.init(Expdesc.VCALL, fs.kCodeABC(Lua.OP_CALL, base, nparams+1, 2));
    fs.kFixline(line);
    fs.freereg = base+1;        // call removes functions and arguments
                // and leaves (unless changed) one result.
  }

  private void prefixexp(Expdesc v) throws IOException
  {
    // prefixexp -> NAME | '(' expr ')'
    switch (token)
    {
      case '(':
      {
        int line = linenumber;
        xNext();
        expr(v);
        check_match(')', '(', line);
        fs.kDischargevars(v);
        return;
      }
      case TK_NAME:
        singlevar(v);
        return;
      default:
        xSyntaxerror("unexpected symbol");
        return;
    }
  }

  private void primaryexp(Expdesc v) throws IOException
  {
    // primaryexp ->
    //    prefixexp { '.' NAME | '[' exp ']' | ':' NAME funcargs | funcargs }
    prefixexp(v);
    while (true)
    {
      switch (token)
      {
        case '.':  /* field */
          field(v);
          break;

        case '[':  /* `[' exp1 `]' */
          {
            Expdesc key = new Expdesc();
            fs.kExp2anyreg(v);
            yindex(key);
            fs.kIndexed(v, key);
          }
          break;

        case ':':  /* `:' NAME funcargs */
          {
            Expdesc key = new Expdesc() ;
            xNext();
            checkname(key);
            fs.kSelf(v, key);
            funcargs(v);
          }
          break;

        case '(':
        case TK_STRING:
        case '{':     // funcargs
          fs.kExp2nextreg(v);
          funcargs(v);
          break;

        default:
          return;
      }
    }
  }

  private void retstat() throws IOException
  {
    // stat -> RETURN explist
    xNext();    // skip RETURN
    // registers with returned values (first, nret)
    int first = 0;
    int nret;
    if (block_follow(token) || token == ';')
    {
      // return no values
      first = 0;
      nret = 0;
    }
    else
    {
      Expdesc e = new Expdesc();
      nret = explist1(e);
      if (hasmultret(e.k))
      {
        fs.kSetmultret(e);
        if (e.k == Expdesc.VCALL && nret == 1)    /* tail call? */
        {
          fs.setcode(e, Lua.SET_OPCODE(fs.getcode(e), Lua.OP_TAILCALL));
          //# assert Lua.ARGA(fs.getcode(e)) == fs.nactvar
        }
        first = fs.nactvar;
        nret = Lua.MULTRET;  /* return all values */
      }
      else
      {
        if (nret == 1)          // only one single value?
        {
          first = fs.kExp2anyreg(e);
        }
        else
        {
          fs.kExp2nextreg(e);  /* values must go to the `stack' */
          first = fs.nactvar;  /* return all `active' values */
          //# assert nret == fs.freereg - first
        }
      }
    }
    fs.kRet(first, nret);
  }

  private void simpleexp(Expdesc v) throws IOException
  {
    // simpleexp -> NUMBER | STRING | NIL | true | false | ... |
    //              constructor | FUNCTION body | primaryexp
    switch (token)
    {
      case TK_NUMBER:
        v.init(Expdesc.VKNUM, 0);
        v.nval = tokenR;
        break;

      case TK_STRING:
        codestring(v, tokenS);
        break;

      case TK_NIL:
        v.init(Expdesc.VNIL, 0);
        break;

      case TK_TRUE:
        v.init(Expdesc.VTRUE, 0);
        break;

      case TK_FALSE:
        v.init(Expdesc.VFALSE, 0);
        break;

      case TK_DOTS:  /* vararg */
        if (!fs.f.isVararg())
          xSyntaxerror("cannot use \"...\" outside a vararg function");
        v.init(Expdesc.VVARARG, fs.kCodeABC(Lua.OP_VARARG, 0, 1, 0));
        break;

      case '{':   /* constructor */
        constructor(v);
        return;

      case TK_FUNCTION:
        xNext();
        body(v, false, linenumber);
        return;

      default:
        primaryexp(v);
        return;
    }
    xNext();
  }

  private boolean statement() throws IOException
  {
    int line = linenumber;
    switch (token)
    {
      case TK_IF:   // stat -> ifstat
        ifstat(line);
        return false;

      case TK_WHILE:  // stat -> whilestat
        whilestat(line);
        return false;

      case TK_DO:       // stat -> DO block END
        xNext();         // skip DO
        block();
        check_match(TK_END, TK_DO, line);
        return false;

      case TK_FOR:      // stat -> forstat
        forstat(line);
        return false;

      case TK_REPEAT:   // stat -> repeatstat
        repeatstat(line);
        return false;

      case TK_FUNCTION:
        funcstat(line); // stat -> funcstat
        return false;

      case TK_LOCAL:    // stat -> localstat
        xNext();         // skip LOCAL
        if (testnext(TK_FUNCTION))  // local function?
          localfunc();
        else
          localstat();
        return false;

      case TK_RETURN:
        retstat();
        return true;  // must be last statement

      case TK_BREAK:  // stat -> breakstat
        xNext();       // skip BREAK
        breakstat();
        return true;  // must be last statement

      default:
        exprstat();
        return false;
    }
  }

  // grep "ORDER OPR" if you change these enums.
  // default access so that FuncState can access them.
  static final int OPR_ADD = 0;
  static final int OPR_SUB = 1;
  static final int OPR_MUL = 2;
  static final int OPR_DIV = 3;
  static final int OPR_MOD = 4;
  static final int OPR_POW = 5;
  static final int OPR_CONCAT = 6;
  static final int OPR_NE = 7;
  static final int OPR_EQ = 8;
  static final int OPR_LT = 9;
  static final int OPR_LE = 10;
  static final int OPR_GT = 11;
  static final int OPR_GE = 12;
  static final int OPR_AND = 13;
  static final int OPR_OR = 14;
  static final int OPR_NOBINOPR = 15;

  static final int OPR_MINUS = 0;
  static final int OPR_NOT = 1;
  static final int OPR_LEN = 2;
  static final int OPR_NOUNOPR = 3;

  /** Converts token into binary operator.  */
  private static int getbinopr(int op)
  {
    switch (op)
    {
      case '+': return OPR_ADD;
      case '-': return OPR_SUB;
      case '*': return OPR_MUL;
      case '/': return OPR_DIV;
      case '%': return OPR_MOD;
      case '^': return OPR_POW;
      case TK_CONCAT: return OPR_CONCAT;
      case TK_NE: return OPR_NE;
      case TK_EQ: return OPR_EQ;
      case '<': return OPR_LT;
      case TK_LE: return OPR_LE;
      case '>': return OPR_GT;
      case TK_GE: return OPR_GE;
      case TK_AND: return OPR_AND;
      case TK_OR: return OPR_OR;
      default: return OPR_NOBINOPR;
    }
  }

  private static int getunopr(int op)
  {
    switch (op)
    {
      case TK_NOT: return OPR_NOT;
      case '-': return OPR_MINUS;
      case '#': return OPR_LEN;
      default: return OPR_NOUNOPR;
    }
  }


  // ORDER OPR
  /**
   * Priority table.  left-priority of an operator is
   * <code>priority[op][0]</code>, its right priority is
   * <code>priority[op][1]</code>.  Please do not modify this table.
   */
  private static final int[][] PRIORITY = new int[][]
  {
    {6, 6}, {6, 6}, {7, 7}, {7, 7}, {7, 7},     // + - * / %
    {10, 9}, {5, 4},                // power and concat (right associative)
    {3, 3}, {3, 3},                 // equality and inequality
    {3, 3}, {3, 3}, {3, 3}, {3, 3}, // order
    {2, 2}, {1, 1}                  // logical (and/or)
  };

  /** Priority for unary operators. */
  private static final int UNARY_PRIORITY = 8;

  /**
   * Operator precedence parser.
   * <code>subexpr -> (simpleexp) | unop subexpr) { binop subexpr }</code>
   * where <var>binop</var> is any binary operator with a priority
   * higher than <var>limit</var>.
   */
  private int subexpr(Expdesc v, int limit) throws IOException
  {
    enterlevel();
    int uop = getunopr(token);
    if (uop != OPR_NOUNOPR)
    {
      xNext();
      subexpr(v, UNARY_PRIORITY);
      fs.kPrefix(uop, v);
    }
    else
    {
      simpleexp(v);
    }
    // expand while operators have priorities higher than 'limit'
    int op = getbinopr(token);
    while (op != OPR_NOBINOPR && PRIORITY[op][0] > limit)
    {
      Expdesc v2 = new Expdesc();
      xNext();
      fs.kInfix(op, v);
      // read sub-expression with higher priority
      int nextop = subexpr(v2, PRIORITY[op][1]);
      fs.kPosfix(op, v, v2);
      op = nextop;
    }
    leavelevel();
    return op;
  }

  private void enterblock(FuncState f, BlockCnt bl, boolean isbreakable)
  {
    bl.breaklist = FuncState.NO_JUMP ;
    bl.isbreakable = isbreakable ;
    bl.nactvar = f.nactvar ;
    bl.upval = false ;
    bl.previous = f.bl;
    f.bl = bl;
    //# assert f.freereg == f.nactvar
  }

  private void leaveblock(FuncState f)
  {
    BlockCnt bl = f.bl;
    f.bl = bl.previous;
    removevars(bl.nactvar);
    if (bl.upval)
      f.kCodeABC(Lua.OP_CLOSE, bl.nactvar, 0, 0);
    /* loops have no body */
    //# assert (!bl.isbreakable) || (!bl.upval)
    //# assert bl.nactvar == f.nactvar
    f.freereg = f.nactvar;  /* free registers */
    f.kPatchtohere(bl.breaklist);
  }


/*
** {======================================================================
** Rules for Statements
** =======================================================================
*/


  private void block() throws IOException
  {
    /* block -> chunk */
    BlockCnt bl = new BlockCnt() ;
    enterblock(fs, bl, false);
    chunk();
    //# assert bl.breaklist == FuncState.NO_JUMP
    leaveblock(fs);
  }

  private void breakstat()
  {
    BlockCnt bl = fs.bl;
    boolean upval = false;
    while (bl != null && !bl.isbreakable)
    {
      upval |= bl.upval;
      bl = bl.previous;
    }
    if (bl == null)
      xSyntaxerror("no loop to break");
    if (upval)
      fs.kCodeABC(Lua.OP_CLOSE, bl.nactvar, 0, 0);
    bl.breaklist = fs.kConcat(bl.breaklist, fs.kJump());
  }

  private void funcstat(int line) throws IOException
  {
    /* funcstat -> FUNCTION funcname body */
    Expdesc b = new Expdesc() ;
    Expdesc v = new Expdesc() ;
    xNext();  /* skip FUNCTION */
    boolean needself = funcname(v);
    body(b, needself, line);
    fs.kStorevar(v, b);
    fs.kFixline(line);  /* definition `happens' in the first line */
  }

  private void checknext(int c) throws IOException
  {
    check(c);
    xNext();
  }

  private void parlist() throws IOException
  {
    /* parlist -> [ param { `,' param } ] */
    Proto f = fs.f;
    int nparams = 0;
    if (token != ')')    /* is `parlist' not empty? */
    {
      do
      {
        switch (token)
        {
          case TK_NAME:    /* param -> NAME */
          {
            new_localvar(str_checkname(), nparams++);
            break;
          }
          case TK_DOTS:    /* param -> `...' */
          {
            xNext();
            f.setIsVararg();
            break;
          }
          default: xSyntaxerror("<name> or '...' expected");
        }
      } while ((!f.isVararg()) && testnext(','));
    }
    adjustlocalvars(nparams);
    f.numparams = fs.nactvar ; /* VARARG_HASARG not now used */
    fs.kReserveregs(fs.nactvar);  /* reserve register for parameters */
  }


  private LocVar getlocvar(int i)
  {
    FuncState fstate = fs ;
    return fstate.f.locvars [fstate.actvar[i]] ;
  }

  private void adjustlocalvars(int nvars)
  {
    fs.nactvar += nvars;
    for (; nvars != 0; nvars--)
    {
      getlocvar(fs.nactvar - nvars).startpc = fs.pc;
    }
  }

  private void new_localvarliteral(String v, int n)
  {
    new_localvar(v, n) ;
  }

  private void errorlimit(int limit, String what)
  {
    String msg = fs.f.linedefined == 0 ?
      "main function has more than "+limit+" "+what :
      "function at line "+fs.f.linedefined+" has more than "+limit+" "+what ;
    xLexerror(msg, 0);
  }


  private void yChecklimit(int v, int l, String m)
  {
    if (v > l)
      errorlimit(l,m);
  }

  private void new_localvar(String name, int n)
  {
    yChecklimit(fs.nactvar+n+1, Lua.MAXVARS, "local variables");
    fs.actvar[fs.nactvar+n] = (short)registerlocalvar(name);
  }

  private int registerlocalvar(String varname)
  {
    Proto f = fs.f;
    f.ensureLocvars(L, fs.nlocvars, Short.MAX_VALUE) ;
    f.locvars[fs.nlocvars].varname = varname;
    return fs.nlocvars++;
  }


  private void body(Expdesc e, boolean needself, int line) throws IOException
  {
    /* body ->  `(' parlist `)' chunk END */
    FuncState new_fs = new FuncState(this);
    open_func(new_fs);
    new_fs.f.linedefined = line;
    checknext('(');
    if (needself)
    {
      new_localvarliteral("self", 0);
      adjustlocalvars(1);
    }
    parlist();
    checknext(')');
    chunk();
    new_fs.f.lastlinedefined = linenumber;
    check_match(TK_END, TK_FUNCTION, line);
    close_func();
    pushclosure(new_fs, e);
  }

  private int UPVAL_K(int upvaldesc)
  {
    return (upvaldesc >>> 8) & 0xFF ;
  }
  private int UPVAL_INFO(int upvaldesc)
  {
    return upvaldesc & 0xFF ;
  }
  private int UPVAL_ENCODE(int k, int info)
  {
    //# assert (k & 0xFF) == k && (info & 0xFF) == info
    return ((k & 0xFF) << 8) | (info & 0xFF) ;
  }


  private void pushclosure(FuncState func, Expdesc v)
  {
    Proto f = fs.f;
    f.ensureProtos(L, fs.np) ;
    Proto ff = func.f ;
    f.p[fs.np++] = ff;
    v.init(Expdesc.VRELOCABLE, fs.kCodeABx(Lua.OP_CLOSURE, 0, fs.np-1));
    for (int i=0; i < ff.nups; i++)
    {
      int upvalue = func.upvalues[i] ;
      int o = (UPVAL_K(upvalue) == Expdesc.VLOCAL) ? Lua.OP_MOVE :
                                                     Lua.OP_GETUPVAL;
      fs.kCodeABC(o, 0, UPVAL_INFO(upvalue), 0);
    }
  }

  private boolean funcname(Expdesc v) throws IOException
  {
    /* funcname -> NAME {field} [`:' NAME] */
    boolean needself = false;
    singlevar(v);
    while (token == '.')
      field(v);
    if (token == ':')
    {
      needself = true;
      field(v);
    }
    return needself;
  }

  private void field(Expdesc v) throws IOException
  {
    /* field -> ['.' | ':'] NAME */
    Expdesc key = new Expdesc() ;
    fs.kExp2anyreg(v);
    xNext();  /* skip the dot or colon */
    checkname(key);
    fs.kIndexed(v, key);
  }

  private void repeatstat(int line) throws IOException
  {
    /* repeatstat -> REPEAT block UNTIL cond */
    int repeat_init = fs.kGetlabel();
    BlockCnt bl1 = new BlockCnt();
    BlockCnt bl2 = new BlockCnt();
    enterblock(fs, bl1, true);  /* loop block */
    enterblock(fs, bl2, false);  /* scope block */
    xNext();  /* skip REPEAT */
    chunk();
    check_match(TK_UNTIL, TK_REPEAT, line);
    int condexit = cond();  /* read condition (inside scope block) */
    if (!bl2.upval)    /* no upvalues? */
    {
      leaveblock(fs);  /* finish scope */
      fs.kPatchlist(condexit, repeat_init);  /* close the loop */
    }
    else    /* complete semantics when there are upvalues */
    {
      breakstat();  /* if condition then break */
      fs.kPatchtohere(condexit);  /* else... */
      leaveblock(fs);  /* finish scope... */
      fs.kPatchlist(fs.kJump(), repeat_init);  /* and repeat */
    }
    leaveblock(fs);  /* finish loop */
  }

  private int cond() throws IOException
  {
    /* cond -> exp */
    Expdesc v = new Expdesc() ;
    expr(v);  /* read condition */
    if (v.k == Expdesc.VNIL)
      v.k = Expdesc.VFALSE;  /* `falses' are all equal here */
    fs.kGoiftrue(v);
    return v.f;
  }

  private void open_func(FuncState funcstate)
  {
    Proto f = new Proto(source, 2);  /* registers 0/1 are always valid */
    funcstate.f = f;
    funcstate.ls = this;
    funcstate.L = L;

    funcstate.prev = this.fs;   /* linked list of funcstates */
    this.fs = funcstate;
  }

  private void localstat() throws IOException
  {
    /* stat -> LOCAL NAME {`,' NAME} [`=' explist1] */
    int nvars = 0;
    int nexps;
    Expdesc e = new Expdesc();
    do
    {
      new_localvar(str_checkname(), nvars++);
    } while (testnext(','));
    if (testnext('='))
    {
      nexps = explist1(e);
    }
    else
    {
      e.k = Expdesc.VVOID;
      nexps = 0;
    }
    adjust_assign(nvars, nexps, e);
    adjustlocalvars(nvars);
  }

  private void forstat(int line) throws IOException
  {
    /* forstat -> FOR (fornum | forlist) END */
    BlockCnt bl = new BlockCnt() ;
    enterblock(fs, bl, true);  /* scope for loop and control variables */
    xNext();  /* skip `for' */
    String varname = str_checkname();  /* first variable name */
    switch (token)
    {
      case '=':
        fornum(varname, line);
        break;
      case ',':
      case TK_IN:
        forlist(varname);
        break;
      default:
        xSyntaxerror("\"=\" or \"in\" expected");
    }
    check_match(TK_END, TK_FOR, line);
    leaveblock(fs);  /* loop scope (`break' jumps to this point) */
  }

  private void fornum(String varname, int line) throws IOException
  {
    /* fornum -> NAME = exp1,exp1[,exp1] forbody */
    int base = fs.freereg;
    new_localvarliteral("(for index)", 0);
    new_localvarliteral("(for limit)", 1);
    new_localvarliteral("(for step)", 2);
    new_localvar(varname, 3);
    checknext('=');
    exp1();  /* initial value */
    checknext(',');
    exp1();  /* limit */
    if (testnext(','))
      exp1();  /* optional step */
    else    /* default step = 1 */
    {
      fs.kCodeABx(Lua.OP_LOADK, fs.freereg, fs.kNumberK(1));
      fs.kReserveregs(1);
    }
    forbody(base, line, 1, true);
  }

  private int exp1() throws IOException
  {
    Expdesc e = new Expdesc();
    expr(e);
    int k = e.k;
    fs.kExp2nextreg(e);
    return k;
  }


  private void forlist(String indexname) throws IOException
  {
    /* forlist -> NAME {,NAME} IN explist1 forbody */
    Expdesc e = new Expdesc() ;
    int nvars = 0;
    int base = fs.freereg;
    /* create control variables */
    new_localvarliteral("(for generator)", nvars++);
    new_localvarliteral("(for state)", nvars++);
    new_localvarliteral("(for control)", nvars++);
    /* create declared variables */
    new_localvar(indexname, nvars++);
    while (testnext(','))
      new_localvar(str_checkname(), nvars++);
    checknext(TK_IN);
    int line = linenumber;
    adjust_assign(3, explist1(e), e);
    fs.kCheckstack(3);  /* extra space to call generator */
    forbody(base, line, nvars - 3, false);
  }

  private void forbody(int base, int line, int nvars, boolean isnum)
      throws IOException
  {
    /* forbody -> DO block */
    BlockCnt bl = new BlockCnt() ;
    adjustlocalvars(3);  /* control variables */
    checknext(TK_DO);
    int prep = isnum ? fs.kCodeAsBx(Lua.OP_FORPREP, base, FuncState.NO_JUMP) : fs.kJump();
    enterblock(fs, bl, false);  /* scope for declared variables */
    adjustlocalvars(nvars);
    fs.kReserveregs(nvars);
    block();
    leaveblock(fs);  /* end of scope for declared variables */
    fs.kPatchtohere(prep);
    int endfor = isnum ?
        fs.kCodeAsBx(Lua.OP_FORLOOP, base, FuncState.NO_JUMP) :
        fs.kCodeABC(Lua.OP_TFORLOOP, base, 0, nvars);
    fs.kFixline(line);  /* pretend that `OP_FOR' starts the loop */
    fs.kPatchlist((isnum ? endfor : fs.kJump()), prep + 1);
  }

  private void ifstat(int line) throws IOException
  {
    /* ifstat -> IF cond THEN block {ELSEIF cond THEN block} [ELSE block] END */
    int escapelist = FuncState.NO_JUMP;
    int flist = test_then_block();  /* IF cond THEN block */
    while (token == TK_ELSEIF)
    {
      escapelist = fs.kConcat(escapelist, fs.kJump());
      fs.kPatchtohere(flist);
      flist = test_then_block();  /* ELSEIF cond THEN block */
    }
    if (token == TK_ELSE)
    {
      escapelist = fs.kConcat(escapelist, fs.kJump());
      fs.kPatchtohere(flist);
      xNext();  /* skip ELSE (after patch, for correct line info) */
      block();  /* `else' part */
    }
    else
      escapelist = fs.kConcat(escapelist, flist);

    fs.kPatchtohere(escapelist);
    check_match(TK_END, TK_IF, line);
  }

  private int test_then_block() throws IOException
  {
    /* test_then_block -> [IF | ELSEIF] cond THEN block */
    xNext();  /* skip IF or ELSEIF */
    int condexit = cond();
    checknext(TK_THEN);
    block();  /* `then' part */
    return condexit;
  }

  private void whilestat(int line) throws IOException
  {
    /* whilestat -> WHILE cond DO block END */
    BlockCnt bl = new BlockCnt() ;
    xNext();  /* skip WHILE */
    int whileinit = fs.kGetlabel();
    int condexit = cond();
    enterblock(fs, bl, true);
    checknext(TK_DO);
    block();
    fs.kPatchlist(fs.kJump(), whileinit);
    check_match(TK_END, TK_WHILE, line);
    leaveblock(fs);
    fs.kPatchtohere(condexit);  /* false conditions finish the loop */
  }

  private static boolean hasmultret(int k)
  {
    return k == Expdesc.VCALL || k == Expdesc.VVARARG ;
  }

  private void adjust_assign(int nvars, int nexps, Expdesc e)
  {
    int extra = nvars - nexps;
    if (hasmultret(e.k))
    {
      extra++;  /* includes call itself */
      if (extra < 0)
        extra = 0;
      fs.kSetreturns(e, extra);  /* last exp. provides the difference */
      if (extra > 1)
        fs.kReserveregs(extra-1);
    }
    else
    {
      if (e.k != Expdesc.VVOID)
        fs.kExp2nextreg(e);  /* close last expression */
      if (extra > 0)
      {
        int reg = fs.freereg;
        fs.kReserveregs(extra);
        fs.kNil(reg, extra);
      }
    }
  }

  private void localfunc() throws IOException
  {
    Expdesc b = new Expdesc();
    new_localvar(str_checkname(), 0);
    Expdesc v = new Expdesc(Expdesc.VLOCAL, fs.freereg);
    fs.kReserveregs(1);
    adjustlocalvars(1);
    body(b, false, linenumber);
    fs.kStorevar(v, b);
    /* debug information will only see the variable after this point! */
    fs.getlocvar(fs.nactvar - 1).startpc = fs.pc;
  }

  private void yindex(Expdesc v) throws IOException
  {
    /* index -> '[' expr ']' */
    xNext();  /* skip the '[' */
    expr(v);
    fs.kExp2val(v);
    checknext(']');
  }

  void xLookahead() throws IOException
  {
    //# assert lookahead == TK_EOS
    lookahead = llex();
    lookaheadR = semR ;
    lookaheadS = semS ;
  }

  private void listfield(ConsControl cc) throws IOException
  {
    expr(cc.v);
    yChecklimit(cc.na, Lua.MAXARG_Bx, "items in a constructor");
    cc.na++;
    cc.tostore++;
  }

  private int indexupvalue(FuncState funcstate, String name, Expdesc v)
  {
    Proto f = funcstate.f;
    int oldsize = f.sizeupvalues;
    for (int i=0; i<f.nups; i++)
    {
      int entry = funcstate.upvalues[i] ;
      if (UPVAL_K(entry) == v.k && UPVAL_INFO(entry) == v.info)
      {
        //# assert name.equals(f.upvalues[i])
        return i;
      }
    }
    /* new one */
    yChecklimit(f.nups + 1, Lua.MAXUPVALUES, "upvalues");
    f.ensureUpvals(L, f.nups) ;
    f.upvalues[f.nups] = name;
    //# assert v.k == Expdesc.VLOCAL || v.k == Expdesc.VUPVAL
    funcstate.upvalues[f.nups] = UPVAL_ENCODE(v.k, v.info) ;
    return f.nups++;
  }
}

final class LHSAssign
{
  LHSAssign prev ;
  Expdesc v = new Expdesc() ;

  LHSAssign()
  {
  }
  LHSAssign(LHSAssign prev)
  {
    this.prev = prev ;
  }
}

final class ConsControl
{
  Expdesc v = new Expdesc() ;  /* last list item read */
  Expdesc t;  /* table descriptor */
  int nh;  /* total number of `record' elements */
  int na;  /* total number of array elements */
  int tostore;  /* number of array elements pending to be stored */

  ConsControl(Expdesc t)
  {
    this.t = t ;
  }
}
