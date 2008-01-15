/*

Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Jan 14, 2008
 */

package com.bigdata.rdf.store;

import java.io.File;
import java.io.FilenameFilter;

import org.apache.log4j.Logger;

import com.bigdata.service.BigdataClient;

/**
 * Class designed to connect to an existing bigdata federation using jini and
 * bulk load a data set into that federation.
 * <p>
 * Note: Jini MUST be running.
 * <p>
 * The metadata service and the data services MUST be running
 * <p>
 * Note: The configuration options for the (meta)data services are set in their
 * respective <code>properties</code> files NOT by the System properties!
 * 
 * @todo support distributed client using hash(filename) MOD N to select host
 * 
 * @todo provide dropIndex so that we can guarentee a cleared federation or at
 *       least warn if the data services already have data.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestTripleStoreLoadRateWithExistingJiniFederation {

    protected static Logger log = Logger.getLogger(TestTripleStoreLoadRateWithExistingJiniFederation.class);
    
    /**
     * 
     * <dl>
     * <dt>-DminDataServices</dt>
     * <dd>The minium #of data services that must be available before the
     * client will start (1 or more). In addition, there must be a metadata
     * service available for the client to run.</dd>
     * <dt>-Dnthreads</dt>
     * <dd>#of threads to use <em>per client</em>.</dd>
     * <dt>-DbufferCapacity</dt>
     * <dd>Capacity of the statement buffers.</dd>
     * <dt>-Ddocuments.directory</dr>
     * <dd>The file or directory to be loaded (recursive processing).</dd>
     * </dl>
     * 
     * You must also specify
     * 
     * <pre>
     *      -Djava.security.policy=policy.all
     * </pre>
     * 
     * and probably want to specify
     * 
     * <pre>
     *     -Dcom.sun.jini.jeri.tcp.useNIO=true
     * </pre>
     * 
     * as well.
     * 
     * @todo support coordinate of the client loads, e.g., using map/reduce to
     *       start the clients on each of the servers in the cluster.
     * 
     * <pre>
     * <dt>
     * -Dclient
     * </dt>
     * <dd>
     *   The client host identifier in [0:nclients-1].
     * </dd>
     * <dt>
     * -Dnclients
     * </dt>
     * <dd>
     *   The #of client processes that will share the data load process.  Each
     *    client process MUST be started independently in its own JVM.  All clients
     *    MUST have access to the files to be loaded.
     * </dd>
     </pre>
     * 
     * @todo support load of the ontology as well?
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        
        final int minDataServices = Integer.parseInt(System.getProperty("minDataServices","2")); 

        final int nthreads = Integer.parseInt(System.getProperty("nthreads","20")); 
        
        final int bufferCapacity = Integer.parseInt(System.getProperty("bufferCapacity","100000")); 
        
        final String file = System.getProperty("documents.directory");
  
        if(file==null) throw new RuntimeException("Required property 'documents.directory' was not specified");
        
        /**
         * Starts in {@link #setUp()}.
         */
        BigdataClient client = new BigdataClient(
                new String[] { "src/resources/config/standalone/Client.config"
//                        , BigdataClient.CLIENT_LABEL+groups
                        });

        /*
         * Await at least N data services and one metadata service (otherwise
         * abort).
         */
        final int N = client.awaitServices(minDataServices, 6000/* timeout(ms) */);
        
        System.err.println("Will run with "+N+" data services");
        
        AbstractTripleStore store = new ScaleOutTripleStore(client.connect(),System.getProperties());
        
        new ConcurrentDataLoader(store, nthreads, bufferCapacity,
                new File(file), new FilenameFilter() {

            public boolean accept(File dir, String name) {
//                if(name.endsWith(".owl")) return true;
                return true;
//                return false;
            }
            
        });
        
        client.terminate();
        
        System.out.println("Exiting normally.");
        
        System.exit(0);
        
    }

}
