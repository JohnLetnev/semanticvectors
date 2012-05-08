/**
   Copyright (c) 2008, University of Pittsburgh

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

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Logger;

import pitt.search.semanticvectors.vectors.VectorType;

/**
 * Command line utility for creating semantic vector indexes using the
 * sliding context window approach (see work on HAL, and by Shutze).
 */
public class BuildPositionalIndex {
  public static final Logger logger = Logger.getLogger(
      BuildPositionalIndex.class.getCanonicalName());
  static VectorStore newBasicTermVectors = null;

  public static String usageMessage =
    "BuildPositionalIndex class in package pitt.search.semanticvectors"
    + "\nUsage: java pitt.search.semanticvectors.BuildPositionalIndex PATH_TO_LUCENE_INDEX"
    + "\nBuildPositionalIndex creates file termtermvectors.bin in local directory."
    + "\nOther parameters that can be changed include dimension,"
    + "\n windowlength (size of sliding context window),"
    + "\n    dimension (number of dimensions), vectortype (real, complex, binary)"
    + "\n    seedlength (number of non-zero entries in basic vectors),"
    + "\n    minimum term frequency.\n"
    + "\nTo change these use the command line arguments "
    + "\n  -vectortype [real, complex, or binary]"
    + "\n  -dimension [number of dimensions]"
    + "\n  -seedlength [seed length]"
    + "\n  -mintermfreq [minimum term frequency]"
    + "\n  -initialtermvectors [name of preexisting vectorstore for term vectors]"
    + "\n  -windowradius [window size]"
    + "\n  -positionalmethod [positional indexing method: basic (default), directional (HAL), permutation (Sahlgren 2008)";

  /**
   * Builds term vector stores from a Lucene index - this index must
   * contain TermPositionVectors.
   * @param args
   */
  public static void main (String[] args) throws IllegalArgumentException {
    try {
      args = FlagConfig.parseCommandLineFlags(args);
    } catch (IllegalArgumentException e) {
      System.out.println(usageMessage);
      throw e;
    }

    // Only one argument should remain, the path to the Lucene index.
    if (args.length != 1) {
      System.out.println(usageMessage);
      throw (new IllegalArgumentException("After parsing command line flags, there were "
          + args.length
          + " arguments, instead of the expected 1."));
    }
    String luceneIndex = args[0];

    // If initialtermvectors is defined, read these vectors.
    if (!FlagConfig.initialtermvectors.equals("")) {
      try {
        VectorStoreRAM vsr = new VectorStoreRAM(
            VectorType.valueOf(FlagConfig.vectortype.toUpperCase()), FlagConfig.dimension);
        vsr.initFromFile(FlagConfig.initialtermvectors);
        newBasicTermVectors = vsr;
        VerbatimLogger.info("Using trained index vectors from vector store " + FlagConfig.initialtermvectors);
      } catch (IOException e) {
        logger.info("Could not read from vector store " + FlagConfig.initialtermvectors);
        System.out.println(usageMessage);
        throw new IllegalArgumentException();
      }
    }

    String termFile = FlagConfig.termtermvectorsfile;
    String docFile = FlagConfig.docvectorsfile;

    if (FlagConfig.positionalmethod.equals("permutation")) termFile = FlagConfig.permutedvectorfile;
    else if (FlagConfig.positionalmethod.equals("permutation_plus_basic")) termFile = FlagConfig.permplustermvectorfile;
    else if (FlagConfig.positionalmethod.equals("directional")) termFile = FlagConfig.directionalvectorfile;

    VerbatimLogger.info("Building positional index, Lucene index: " + luceneIndex
        + ", Seedlength: " + FlagConfig.seedlength
        + ", Vector length: " + FlagConfig.dimension
        + ", Vector type: " + FlagConfig.vectortype
        + ", Minimum term frequency: " + FlagConfig.minfrequency
        + ", Maximum term frequency: " + FlagConfig.maxfrequency
        + ", Number non-alphabet characters: " + FlagConfig.maxnonalphabetchars
        + ", Window radius: " + FlagConfig.windowradius
        + ", Fields to index: " + Arrays.toString(FlagConfig.contentsfields)
        + "\n");

    try {
      TermTermVectorsFromLucene vecStore = new TermTermVectorsFromLucene(
          luceneIndex,  VectorType.valueOf(FlagConfig.vectortype.toUpperCase()),
          FlagConfig.dimension, FlagConfig.seedlength, FlagConfig.minfrequency, FlagConfig.maxfrequency,
          FlagConfig.maxnonalphabetchars, 2 * FlagConfig.windowradius + 1, FlagConfig.positionalmethod,
            newBasicTermVectors, FlagConfig.contentsfields);
      
      VectorStoreWriter.writeVectors(termFile, vecStore);

      for (int i = 1; i < FlagConfig.trainingcycles; ++i) {
        newBasicTermVectors = vecStore.getBasicTermVectors();
        VerbatimLogger.info("\nRetraining with learned term vectors ...");
        vecStore = new TermTermVectorsFromLucene(
            luceneIndex,  VectorType.valueOf(FlagConfig.vectortype.toUpperCase()),
            FlagConfig.dimension, FlagConfig.seedlength, FlagConfig.minfrequency, FlagConfig.maxfrequency,
            FlagConfig.maxnonalphabetchars, 2 * FlagConfig.windowradius + 1, FlagConfig.positionalmethod,
            newBasicTermVectors, FlagConfig.contentsfields);
      }

      if (FlagConfig.trainingcycles > 1) {
        termFile = termFile.replaceAll("\\..*", "") + FlagConfig.trainingcycles + ".bin";
        docFile = "docvectors" + FlagConfig.trainingcycles + ".bin";
        VectorStoreWriter.writeVectors(termFile, vecStore);
      }

      if (!FlagConfig.docindexing.equals("none")) {
        IncrementalDocVectors.createIncrementalDocVectors(
            vecStore, luceneIndex, FlagConfig.contentsfields, docFile);
        //System.exit(0);
      }
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }
}