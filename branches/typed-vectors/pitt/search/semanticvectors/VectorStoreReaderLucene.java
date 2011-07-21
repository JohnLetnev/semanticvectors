/**
   Copyright (c) 2007, University of Pittsburgh
   Copyright (c) 2008 and ongoing, the SemanticVectors AUTHORS.

   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

 * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided
   with the distribution.

 * Neither the name of the University of Pittsburgh nor the names
   of its contributors may be used to endorse or promote products
   derived from this software without specific prior written
   permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **/

package pitt.search.semanticvectors;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.logging.Logger;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IndexInput;

import pitt.search.semanticvectors.vectors.Vector;
import pitt.search.semanticvectors.vectors.VectorFactory;

/**
   This class provides methods for reading a VectorStore from disk. <p>

   The serialization currently presumes that the object (in the ObjectVectors)
   should be serialized as a String. <p>

   The implementation uses Lucene's I/O package, which proved much faster
   than the native java.io.DataOutputStream
   @see ObjectVector
 **/
public class VectorStoreReaderLucene implements CloseableVectorStore {
  private static final Logger logger = Logger.getLogger(
      VectorStoreReaderLucene.class.getCanonicalName());

  private String vectorFileName;
  private File vectorFile;
  private FSDirectory fsDirectory;

  private ThreadLocal<IndexInput> threadLocalIndexInput;

  public FSDirectory fsDirectory() {
    return this.fsDirectory;
  }

  public IndexInput getIndexInput() {
    return threadLocalIndexInput.get();
  }
  
  public VectorStoreReaderLucene(String vectorFileName) throws IOException {
    this.vectorFileName = vectorFileName;
    this.vectorFile = new File(vectorFileName);
    try {
      String parentPath = this.vectorFile.getParent();
      if (parentPath == null) parentPath = "";
      this.fsDirectory = FSDirectory.open(new File(parentPath));
      // Read number of dimension from header information.
      this.threadLocalIndexInput = new ThreadLocal<IndexInput>() {
        @Override
        protected IndexInput initialValue() {
          try {
            return fsDirectory.openInput(vectorFile.getName());
          } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
          }
        }
      };
      readHeadersFromIndexInput();
    } catch (IOException e) {
      logger.warning("Cannot open file: " + this.vectorFileName + "\n" + e.getMessage());
      throw e;
    }
  }
  
  public VectorStoreReaderLucene(ThreadLocal<IndexInput> threadLocalIndexInput) throws IOException {
    this.threadLocalIndexInput = threadLocalIndexInput;
    readHeadersFromIndexInput();
  }

  public void readHeadersFromIndexInput() throws IOException {
    String header = threadLocalIndexInput.get().readString();
    Flags.parseFlagsFromString(header);
  }

  public void close() {
    this.closeIndexInput();
    this.fsDirectory.close();
  }

  public void closeIndexInput() {
    try {
      this.getIndexInput().close();
    } catch (IOException e) {
      logger.info("Cannot close resources from file: " + this.vectorFile
          + "\n" + e.getMessage());
    }
  }

  public Enumeration<ObjectVector> getAllVectors() {
    try {
      getIndexInput().seek(0);
      // Skip header line.
      getIndexInput().readString();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    return new VectorEnumeration(getIndexInput());
  }

  /**
   * Given an object, get its corresponding vector <br>
   * This implementation only works for string objects so far <br>
   * 
   * @param desiredObject - the string you're searching for
   * @return vector from the VectorStore, or null if not found.
   */
  public Vector getVector(Object desiredObject) {
    try {
      String stringTarget = desiredObject.toString();
      getIndexInput().seek(0);
      // Skip header line.
      getIndexInput().readString();
      while (getIndexInput().getFilePointer() < getIndexInput().length() - 1) {
        String objectString = getIndexInput().readString();
        if (objectString.equals(stringTarget)) {
          logger.info("Found vector for '" + stringTarget + "'");
          Vector vector = VectorFactory.createZeroVector(Flags.vectortype, Flags.dimension);
          vector.readFromLuceneStream(getIndexInput());
          return vector;
        }
        else{
          getIndexInput().seek(getIndexInput().getFilePointer()
              + VectorFactory.getLuceneByteSize(Flags.vectortype, Flags.dimension));
        }
      }
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    logger.info("Didn't find vector for '" + desiredObject + "'");
    return null;
  }

  /**
   * Trivial (costly) implementation of getNumVectors that iterates and counts vectors.
   */
  public int getNumVectors() {
    Enumeration<ObjectVector> allVectors = this.getAllVectors();
    int i = 0;
    while (allVectors.hasMoreElements()) {
      allVectors.nextElement();
      ++i;
    }
    return i;
  }

  /**
   * Implements the hasMoreElements() and nextElement() methods
   * to give Enumeration interface from store on disk.
   */
  public class VectorEnumeration implements Enumeration<ObjectVector> {
    IndexInput indexInput;

    public VectorEnumeration(IndexInput indexInput) {
      this.indexInput = indexInput;
    }

    public boolean hasMoreElements() {
      return (indexInput.getFilePointer() < indexInput.length());
    }

    public ObjectVector nextElement() {
      String object = null;
      Vector vector = VectorFactory.createZeroVector(Flags.vectortype, Flags.dimension);
      try {
        object = indexInput.readString();
        vector.readFromLuceneStream(indexInput);
      }
      catch (IOException e) {
        e.printStackTrace();
      }
      return new ObjectVector(object, vector);
    }
  }
}
