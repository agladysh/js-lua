
package java.io;

public abstract class Reader {
  public void mark(int l) throws IOException
  {
  }

  public void reset() throws IOException
  {
  }

  public int read() throws IOException
  {
	  return 0;
  }
  
  public boolean markSupported()
  {
		return false;
  }
}
