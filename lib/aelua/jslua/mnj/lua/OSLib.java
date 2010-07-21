/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/OSLib.java#1 $
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

// REFERENCES
// [C1990] "ISO Standard: Programming languages - C"; ISO 9899:1990;

package mnj.lua;

//import java.util.Calendar;
//import java.util.Date;
//import java.util.TimeZone;

/**
 * The OS Library.  Can be opened into a {@link Lua} state by invoking
 * the {@link #open} method.
 */
public final class OSLib extends LuaJavaCallback
{
  // Each function in the library corresponds to an instance of
  // this class which is associated (the 'which' member) with an integer
  // which is unique within this class.  They are taken from the following
  // set.
  private static final int CLOCK = 1;
  private static final int DATE = 2;
  private static final int DIFFTIME = 3;
  // EXECUTE = 4;
  // EXIT = 5;
  // GETENV = 6;
  // REMOVE = 7;
  // RENAME = 8;
  private static final int SETLOCALE = 9;
  private static final int TIME = 10;

  /**
   * Which library function this object represents.  This value should
   * be one of the "enums" defined in the class.
   */
  private int which;

  /** Constructs instance, filling in the 'which' member. */
  private OSLib(int which)
  {
    this.which = which;
  }

  /**
   * Implements all of the functions in the Lua os library (that are
   * provided).  Do not call directly.
   * @param L  the Lua state in which to execute.
   * @return number of returned parameters, as per convention.
   */
  public int luaFunction(Lua L)
  {
    switch (which)
    {
      case CLOCK:
        return clock(L);
      case DATE:
        return date(L);
      case DIFFTIME:
        return difftime(L);
      case SETLOCALE:
        return setlocale(L);
      case TIME:
        return time(L);
    }
    return 0;
  }

  /**
   * Opens the library into the given Lua state.  This registers
   * the symbols of the library in the table "os".
   * @param L  The Lua state into which to open.
   */
  public static void open(Lua L)
  {
    L.register("os");

    r(L, "clock", CLOCK);
    r(L, "date", DATE);
    r(L, "difftime", DIFFTIME);
    r(L, "setlocale", SETLOCALE);
    r(L, "time", TIME);
  }

  /** Register a function. */
  private static void r(Lua L, String name, int which)
  {
    OSLib f = new OSLib(which);
    Object lib = L.getGlobal("os");
    L.setField(lib, name, f);
  }

  private static final long T0 = System.currentTimeMillis();

  /** Implements clock.  Java provides no way to get CPU time, so we
   * return the amount of wall clock time since this class was loaded.
   */
  private static int clock(Lua L)
  {
    double d = (double)System.currentTimeMillis();
    d = d - T0;
    d /= 1000;

    L.pushNumber(d);
    return 1;
  }

  /** Implements date. */
  private static int date(Lua L)
  {
/*
 *     long t;
    if (L.isNoneOrNil(2))
    {
      t = System.currentTimeMillis();
    }
    else
    {
      t = (long)(L.checkNumber(2)*1000); //FIX ms to s
    }

    String s = L.optString(1, "%c");
    TimeZone tz = TimeZone.getDefault();
    if (s.startsWith("!"))
    {
      tz = TimeZone.getTimeZone("GMT");
      s = s.substring(1);
    }

    Calendar c = Calendar.getInstance(tz);
    c.setTime(new Date(t));

    if (s.equals("*t"))
    {
      L.push(L.createTable(0, 8));      // 8 = number of fields
      setfield(L, "sec", c.get(Calendar.SECOND));
      setfield(L, "min", c.get(Calendar.MINUTE));
      setfield(L, "hour", c.get(Calendar.HOUR));
      setfield(L, "day", c.get(Calendar.DAY_OF_MONTH));
      setfield(L, "month", canonicalmonth(c.get(Calendar.MONTH)));
      setfield(L, "year", c.get(Calendar.YEAR));
      setfield(L, "wday", canonicalweekday(c.get(Calendar.DAY_OF_WEEK)));
      // yday is not supported because CLDC 1.1 does not provide it.
      // setfield(L, "yday", c.get("???"));
      if (tz.useDaylightTime())
      {
        // CLDC 1.1 does not provide any way to determine isdst, so we set
        // it to -1 (which in C means that the information is not
        // available).
        setfield(L, "isdst", -1);
      }
      else
      {
        // On the other hand if the timezone does not do DST then it
        // can't be in effect.
        setfield(L, "isdst", 0);
      }
    }
    else
    {
      StringBuffer b = new StringBuffer();
      int i = 0;
      int l = s.length();
      while (i < l)
      {
        char ch = s.charAt(i);
        ++i;
        if (ch != '%')
        {
          b.append(ch);
          continue;
        }
        if (i >= l)
        {
          break;
        }
        ch = s.charAt(i);
        ++i;
        // Generally in order to save space, the abbreviated forms are
        // identical to the long forms.
        // The specifiers are from [C1990].
        switch (ch)
        {
          case 'a': case 'A':
            b.append(weekdayname(c));
            break;
          case 'b': case 'B':
            b.append(monthname(c));
            break;
          case 'c':
            b.append(c.getTime().toString());
            break;
          case 'd':
            b.append(format(c.get(Calendar.DAY_OF_MONTH), 2));
            break;
          case 'H':
            b.append(format(c.get(Calendar.HOUR), 2));
            break;
          case 'I':
            {
              int h = c.get(Calendar.HOUR);
              h = (h+11)%12+1;  // force into range 1-12
              b.append(format(h, 2));
            }
            break;
          case 'j':
          case 'U': case 'W':
            // Not supported because CLDC 1.1 doesn't provide it.
            b.append('%');
            b.append(ch);
            break;
          case 'm':
            {
              int m = canonicalmonth(c.get(Calendar.MONTH));
              b.append(format(m, 2));
            }
            break;
          case 'M':
            b.append(format(c.get(Calendar.MINUTE), 2));
            break;
          case 'p':
            {
              int h = c.get(Calendar.HOUR);
              b.append(h<12 ? "am" : "pm");
            }
            break;
          case 'S':
            b.append(format(c.get(Calendar.SECOND), 2));
            break;
          case 'w':
            b.append(canonicalweekday(c.get(Calendar.DAY_OF_WEEK)));
            break;
          case 'x':
            {
              String u = c.getTime().toString();
              // We extract fields from the result of Date.toString.
              // The output of which is of the form:
              // dow mon dd hh:mm:ss zzz yyyy
              // except that zzz is optional.
              b.append(u.substring(0, 11));
              b.append(c.get(Calendar.YEAR));
            }
            break;
          case 'X':
            {
              String u = c.getTime().toString();
              b.append(u.substring(11, u.length()-5));
            }
            break;
          case 'y':
            b.append(format(c.get(Calendar.YEAR) % 100, 2));
            break;
          case 'Y':
            b.append(c.get(Calendar.YEAR));
            break;
          case 'Z':
            b.append(tz.getID());
            break;
          case '%':
            b.append('%');
            break;
        }
      } /* while *
      L.pushString(b.toString());
    }
    return 1;
 */
	return 0;
  }

  /** Implements difftime. */
  private static int difftime(Lua L)
  {
    L.pushNumber((L.checkNumber(1) - L.optNumber(2, 0))); //FIX ms to s
    return 1;
  }

  // Incredibly, the spec doesn't give a numeric value and range for
  // Calendar.JANUARY through to Calendar.DECEMBER.
  /**
   * Converts from 0-11 to required Calendar value.  DO NOT MODIFY THIS
   * ARRAY.
   */
/*
 *   private static final int[] MONTH =
  {
    Calendar.JANUARY,
    Calendar.FEBRUARY,
    Calendar.MARCH,
    Calendar.APRIL,
    Calendar.MAY,
    Calendar.JUNE,
    Calendar.JULY,
    Calendar.AUGUST,
    Calendar.SEPTEMBER,
    Calendar.OCTOBER,
    Calendar.NOVEMBER,
    Calendar.DECEMBER
  };
*/

  /** Implements setlocale. */
  private static int setlocale(Lua L)
  {
	  return 0;
/*
 *     if (L.isNoneOrNil(1))
    {
      L.pushString("");
    }
    else
    {
      L.pushNil();
    }
    return 1;*/
  }

  /** Implements time. */
  private static int time(Lua L)
  {
    if (L.isNoneOrNil(1))       // called without args?
    {
      L.pushNumber(System.currentTimeMillis()/1000.0); //FIX ms to s
      return 1;
    }
    return 0;
    /*
    L.checkType(1, Lua.TTABLE);
    L.setTop(1);        // make sure table is at the top
    Calendar c = Calendar.getInstance();
    c.set(Calendar.SECOND, getfield(L, "sec", 0));
    c.set(Calendar.MINUTE, getfield(L, "min", 0));
    c.set(Calendar.HOUR, getfield(L, "hour", 12));
    c.set(Calendar.DAY_OF_MONTH, getfield(L, "day", -1));
    c.set(Calendar.MONTH, MONTH[getfield(L, "month", -1) - 1]);
    c.set(Calendar.YEAR, getfield(L, "year", -1));
    // ignore isdst field
    L.pushNumber(c.getTime().getTime()/1000.0); //FIX ms to s
    return 1;
    * */
  }

  private static int getfield(Lua L, String key, int d)
  {
    Object o = L.getField(L.value(-1), key);
    if (L.isNumber(o))
      return (int)L.toNumber(o);
    if (d < 0)
      return L.error("field '" + key + "' missing in date table");
    return d;
  }

  private static void setfield(Lua L, String key, int value)
  {
    L.setField(L.value(-1), key, L.valueOfNumber(value));
  }

  /** Format a positive integer in a 0-filled field of width
   * <var>w</var>.
   */
  private static String format(int i, int w)
  {
    StringBuffer b = new StringBuffer();
    b.append(i);
    while (b.length() < w)
    {
      b.insert(0, '0');
    }
    return b.toString();
  }
/*
  private static String weekdayname(Calendar c)
  {
    String s = c.getTime().toString();
    return s.substring(0, 3);
  }

  private static String monthname(Calendar c)
  {
    String s = c.getTime().toString();
    return s.substring(4, 7);
  }

  /**
   * (almost) inverts the conversion provided by {@link #MONTH}.  Converts
   * from a {@link Calendar} value to a month in the range 1-12.
   * @param m  a value from the enum Calendar.JANUARY, Calendar.FEBRUARY, etc
   * @return a month in the range 1-12, or the original value.
   *
  private static int canonicalmonth(int m)
  {
    for (int i=0; i<MONTH.length; ++i)
    {
      if (m == MONTH[i])
      {
        return i+1;
      }
    }
    return m;
  }

  // DO NOT MODIFY ARRAY
  private static final int[] WEEKDAY =
  {
    Calendar.SUNDAY,
    Calendar.MONDAY,
    Calendar.TUESDAY,
    Calendar.WEDNESDAY,
    Calendar.THURSDAY,
    Calendar.FRIDAY,
    Calendar.SATURDAY,
  };

  /**
   * Converts from a {@link Calendar} value to a weekday in the range
   * 0-6 where 0 is Sunday (as per the convention used in [C1990]).
   * @param w  a value from the enum Calendar.SUNDAY, Calendar.MONDAY, etc
   * @return a weekday in the range 0-6, or the original value.
   *
  private static int canonicalweekday(int w)
  {
    for (int i=0; i<WEEKDAY.length; ++i)
    {
      if (w == WEEKDAY[i])
      {
        return i;
      }
    }
    return w;
  }
  */
}
