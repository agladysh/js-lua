/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/LuaTable.java#1 $
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

import java.util.Iterator;
import java.util.Enumeration;

//patched to hashmap?
//GWT doesnt have hashtable

/**
 * Class that models Lua's tables.  Each Lua table is an instance of
 * this class.  Whilst you can clearly see that this class extends
 * {@link java.util.Hashtable} you should in no way rely upon that.
 * Calling any methods that are not defined in this class (but are
 * defined in a super class) is extremely deprecated.
 */
public final class LuaTable extends java.util.HashMap
{
  private static final int MAXBITS = 26;
  private static final int MAXASIZE = 1 << MAXBITS;

  private LuaTable metatable;   // = null;
  private static final Object[] ZERO = new Object[0];
  /**
   * Array used so that tables accessed like arrays are more efficient.
   * All elements stored at an integer index, <var>i</var>, in the
   * range [1,sizeArray] are stored at <code>array[i-1]</code>.
   * This speed and space usage for array-like access.
   * When the table is rehashed the array's size is chosen to be the
   * largest power of 2 such that at least half the entries are
   * occupied.  Default access granted for {@link Enum} class, do not
   * abuse.
   */
  Object[] array = ZERO;
  /**
   * Equal to <code>array.length</code>.  Default access granted for
   * {@link Enum} class, do not abuse.
   */
  int sizeArray;        // = 0;
  /**
   * <code>true</code> whenever we are in the {@link #rehash}
   * method.  Avoids infinite rehash loops.
   */
  private boolean inrehash;     // = false;

  LuaTable()
  {
    super(1);
  }

  /**
   * Fresh LuaTable with hints for preallocating to size.
   * @param narray  number of array slots to preallocate.
   * @param nhash   number of hash slots to preallocate.
   */
  LuaTable(int narray, int nhash)
  {
    // :todo: super(nhash) isn't clearly correct as adding nhash hash
    // table entries will causes a rehash with the usual implementation
    // (which rehashes when ratio of entries to capacity exceeds the
    // load factor of 0.75).  Perhaps ideally we would size the hash
    // tables such that adding nhash entries will not cause a rehash.
    super(nhash);
    array = new Object[narray];
    for (int i=0; i<narray; ++i)
    {
      array[i] = Lua.NIL;
    }
    sizeArray = narray;
  }

  /**
   * Implements discriminating equality.  <code>o1.equals(o2) == (o1 ==
   * o2) </code>.  This method is not necessary in CLDC, it's only
   * necessary in J2SE because java.util.Hashtable overrides equals.
   * @param o  the reference to compare with.
   * @return true when equal.
   */
  public boolean equals(Object o)
  {
    return this == o;
  }

  /**
   * Provided to avoid Checkstyle warning.  This method is not necessary
   * for correctness (in neither JME nor JSE), it's only provided to
   * remove a Checkstyle warning.
   * Since {@link #equals} implements the most discriminating
   * equality possible, this method can have any implementation.
   * @return an int.
   */
  public int hashCode()
  {
    return System.identityHashCode(this);
  }

  private static int arrayindex(Object key)
  {
    if (key instanceof Double)
    {
      double d = ((Double)key).doubleValue();
      int k = (int)d;
      if (k == d)
      {
        return k;
      }
    }
    return -1;  // 'key' did not match some condition
  }

  private static int computesizes(int[] nums, int[] narray)
  {
    final int t = narray[0];
    int a = 0;  // number of elements smaller than 2^i
    int na = 0; // number of elements to go to array part
    int n = 0;  // optimal size for array part
    int twotoi = 1;     // 2^i
    for (int i=0; twotoi/2 < t; ++i)
    {
      if (nums[i] > 0)
      {
        a += nums[i];
        if (a > twotoi/2)       // more than half elements present?
        {
          n = twotoi;   // optimal size (till now)
          na = a;       // all elements smaller than n will go to array part
        }
      }
      if (a == t)       // all elements already counted
      {
        break;
      }
      twotoi *= 2;
    }
    narray[0] = n;
    //# assert narray[0]/2 <= na && na <= narray[0]
    return na;
  }

  private int countint(Object key, int[] nums)
  {
    int k = arrayindex(key);
    if (0 < k && k <= MAXASIZE) // is 'key' an appropriate array index?
    {
      ++nums[ceillog2(k)];      // count as such
      return 1;
    }
    return 0;
  }

  private int numusearray(int[] nums)
  {
    int ause = 0;       // summation of 'nums'
    int i = 1;  // count to traverse all array keys
    int ttlg = 1;       // 2^lg
    for(int lg = 0; lg <= MAXBITS; ++lg)        // for each slice
    {
      int lc = 0;       // counter
      int lim = ttlg;
      if (lim > sizeArray)
      {
        lim = sizeArray;        // adjust upper limit
        if (i > lim)
        {
          break;        // no more elements to count
        }
      }
      // count elements in range (2^(lg-1), 2^lg]
      for (; i <= lim; ++i)
      {
        if (array[i-1] != Lua.NIL)
        {
          ++lc;
        }
      }
      nums[lg] += lc;
      ause += lc;
      ttlg *= 2;
    }
    return ause;
  }

  private int numusehash(int[] nums, int[] pnasize)
  {
    int totaluse = 0;   // total number of elements
    int ause = 0;       // summation of nums
    Iterator e;
    e = (Iterator)super.values();
    while (e.hasNext())
    {
      Object o =e. next();
      ause += countint(o, nums);
      ++totaluse;
    }
    pnasize[0] += ause;
    return totaluse;
  }

  /**
   * @param nasize  (new) size of array part
   */
  private void resize(int nasize)
  {
    if (nasize == sizeArray)
    {
      return;
    }
    Object[] newarray = new Object[nasize];
    if (nasize > sizeArray)     // array part must grow?
    {
      // The new array slots, from sizeArray to nasize-1, must
      // be filled with their values from the hash part.
      // There are two strategies:
      // Iterate over the new array slots, and look up each index in the
      // hash part to see if it has a value; or,
      // Iterate over the hash part and see if each key belongs in the
      // array part.
      // For now we choose the first algorithm.
      // :todo: consider using second algorithm, possibly dynamically.
      System.arraycopy(array, 0, newarray, 0, array.length);
      for (int i=array.length; i<nasize; ++i)
      {
        Object key = new Double(i+1);
        Object v = super.remove(key);
        if (v == null)
        {
          v = Lua.NIL;
        }
        newarray[i] = v;
      }
    }
    if (nasize < sizeArray)     // array part must shrink?
    {
      // move elements from array slots nasize to sizeArray-1 to the
      // hash part.
      for (int i=nasize; i<sizeArray; ++i)
      {
        if (array[i] != Lua.NIL)
        {
          Object key = new Double(i+1);
          super.put(key, array[i]);
        }
      }
      System.arraycopy(array, 0, newarray, 0, newarray.length);
    }
    array = newarray;
    sizeArray = array.length;
  }

  protected void rehash()
  {
    boolean oldinrehash = inrehash;
    inrehash = true;
    if (!oldinrehash)
    {
      int[] nasize = new int[1];
      int[] nums = new int[MAXBITS+1];
      nasize[0] = numusearray(nums);      // count keys in array part
      int totaluse = nasize[0];
      totaluse += numusehash(nums, nasize);
      int na = computesizes(nums, nasize);

      resize(nasize[0]);
    }
//    super.rehash();
    inrehash = oldinrehash;
  }

  /**
   * Getter for metatable member.
   * @return  The metatable.
   */
  LuaTable getMetatable()
  {
    return metatable;
  }
  /**
   * Setter for metatable member.
   * @param metatable  The metatable.
   */
  // :todo: Support metatable's __gc and __mode keys appropriately.
  //        This involves detecting when those keys are present in the
  //        metatable, and changing all the entries in the Hashtable
  //        to be instance of java.lang.Ref as appropriate.
  void setMetatable(LuaTable metatable)
  {
    this.metatable = metatable;
    return;
  }

  /**
   * Supports Lua's length (#) operator.  More or less equivalent to
   * luaH_getn and unbound_search in ltable.c.
   */
  int getn()
  {
    int j = sizeArray;
    if (j > 0 && array[j-1] == Lua.NIL)
    {
      // there is a boundary in the array part: (binary) search for it
      int i = 0;
      while (j - i > 1)
      {
        int m = (i+j)/2;
        if (array[m-1] == Lua.NIL)
        {
          j = m;
        }
        else
        {
          i = m;
        }
      }
      return i;
    }

    // unbound_search

    int i = 0;
    j = 1;
    // Find 'i' and 'j' such that i is present and j is not.
    while (this.getnum(j) != Lua.NIL)
    {
      i = j;
      j *= 2;
      if (j < 0)        // overflow
      {
        // Pathological case.  Linear search.
        i = 1;
        while (this.getnum(i) != Lua.NIL)
        {
          ++i;
        }
        return i-1;
      }
    }
    // binary search between i and j
    while (j - i > 1)
    {
      int m = (i+j)/2;
      if (this.getnum(m) == Lua.NIL)
      {
        j = m;
      }
      else
      {
        i = m;
      }
    }
    return i;
  }

  /**
   * Like {@link java.util.Hashtable#get}.  Ensures that indexes
   * with no value return {@link Lua#NIL}.  In order to get the correct
   * behaviour for <code>t[nil]</code>, this code assumes that Lua.NIL
   * is non-<code>null</code>.
   */
  public Object getlua(Object key)
  {
    if (key instanceof Double)
    {
      double d = ((Double)key).doubleValue();
      if (d <= sizeArray && d >=1)
      {
        int i = (int)d;
        if (i == d)
        {
          return array[i-1];
        }
      }
    }
    Object r = super.get(key);
    if (r == null)
    {
      r = Lua.NIL;
    }
    return r;
  }

  /**
   * Like {@link #getlua(Object)} but the result is written into
   * the <var>value</var> {@link Slot}.
   */
  public void getlua(Slot key, Slot value)
  {
    if (key.r == Lua.NUMBER)
    {
      double d = key.d;
      if (d <= sizeArray && d >= 1)
      {
        int i = (int)d;
        if (i == d)
        {
          value.setObject(array[i-1]);
          return;
        }
      }
    }
    Object r = super.get(key.asObject());
    if (r == null)
    {
      r = Lua.NIL;
    }
    value.setObject(r);
  }

  /** Like get for numeric (integer) keys. */
  public Object getnum(int k)
  {
    if (k <= sizeArray && k >= 1)
    {
      return array[k-1];
    }
    Object r = super.get(new Double(k));
    if (r == null)
    {
      return Lua.NIL;
    }
    return r;
  }

  /**
   * Like {@link java.util.Hashtable#put} but enables Lua's semantics
   * for <code>nil</code>;
   * In particular that <code>x = nil</nil>
   * deletes <code>x</code>.
   * And also that <code>t[nil]</code> raises an error.
   * Generally, users of Jill should be using
   * {@link Lua#setTable} instead of this.
   * @param key key.
   * @param value value.
   */
  public void putlua(Lua L, Object key, Object value)
  {
    double d = 0.0;
    int i = Integer.MAX_VALUE;

    if (key == Lua.NIL)
    {
      L.gRunerror("table index is nil");
    }
    if (key instanceof Double)
    {
      d = ((Double)key).doubleValue();
      int j = (int)d;

      if (j == d && j >= 1)
      {
        i = j; // will cause additional check for array part later if
               // the array part check fails now.
        if (i <= sizeArray)
        {
          array[i-1] = value;
          return;
        }
      }
      if (Double.isNaN(d))
      {
        L.gRunerror("table index is NaN");
      }
    }
    // :todo: Consider checking key for NaN (PUC-Rio does)
    if (value == Lua.NIL)
    {
      remove(key);
      return;
    }
    super.put(key, value);
    // This check is necessary because sometimes the call to super.put
    // can rehash and the new (k,v) pair should be in the array part
    // after the rehash, but is still in the hash part.
    if (i <= sizeArray)
    {
      remove(key);
      array[i-1] = value;
    }
  }

  public void putlua(Lua L, Slot key, Object value)
  {
    int i = Integer.MAX_VALUE;

    if (key.r == Lua.NUMBER)
    {
      int j = (int)key.d;
      if (j == key.d && j >= 1)
      {
        i = j;
        if (i <= sizeArray)
        {
          array[i-1] = value;
          return;
        }
      }
      if (Double.isNaN(key.d))
      {
        L.gRunerror("table index is NaN");
      }
    }
    Object k = key.asObject();
    // :todo: consider some sort of tail merge with the other putlua
    if (value == Lua.NIL)
    {
      remove(k);
      return;
    }
    super.put(k, value);
    if (i <= sizeArray)
    {
      remove(k);
      array[i-1] = value;
    }
  }

  /**
   * Like put for numeric (integer) keys.
   */
  public void putnum(int k, Object v)
  {
    if (k <= sizeArray && k >= 1)
    {
      array[k-1] = v;
      return;
    }
    // The key can never be NIL so putlua will never notice that its L
    // argument is null.
    // :todo: optimisation to avoid putlua checking for array part again
    putlua(null, new Double(k), v);
  }

  /**
   * Do not use, implementation exists only to generate deprecated
   * warning.
   * @deprecated Use getlua instead.
   */
  public Object get(Object key)
  {
    throw new IllegalArgumentException();
  }

  public Enumeration keys()
  {
    return new Enum(this, (Iterator)super.values());
  }

  /**
   * Do not use, implementation exists only to generate deprecated
   * warning.
   * @deprecated Use putlua instead.
   */
  public Object put(Object key, Object value)
  {
    throw new IllegalArgumentException();
  }
  
  /**
   * Used by oLog2.  DO NOT MODIFY.
   */
  private static final byte[] LOG2 = new byte[] {
    0,1,2,2,3,3,3,3,4,4,4,4,4,4,4,4,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
    6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
    8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,
    8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,
    8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,
    8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8
  };

  /**
   * Equivalent to luaO_log2.
   */
  private static int oLog2(int x)
  {
    //# assert x >= 0

    int l = -1;
    while (x >= 256)
    {
      l += 8;
      x >>>= 8;
    }
    return l + LOG2[x];
  }

  private static int ceillog2(int x)
  {
    return oLog2(x-1)+1;
  }
}

final class Enum implements Enumeration
{
  private LuaTable t;
  private int i;        // = 0
  private Iterator e;

  Enum(LuaTable t, Iterator e)
  {
    this.t = t;
    this.e = e;
    inci();
  }

  /**
   * Increments {@link #i} until it either exceeds
   * <code>t.sizeArray</code> or indexes a non-nil element.
   */
  void inci()
  {
    while (i < t.sizeArray && t.array[i] == Lua.NIL)
    {
      ++i;
    }
  }

  public boolean hasMoreElements()
  {
    if (i < t.sizeArray)
    {
      return true;
    }
    return e.hasNext();
  }

  public Object nextElement()
  {
    Object r;
    if (i < t.sizeArray)
    {
      ++i;      // array index i corresponds to key i+1
      r = new Double(i);
      inci();
    }
    else
    {
      r = e.next();
    }
    return r;
  }
}
