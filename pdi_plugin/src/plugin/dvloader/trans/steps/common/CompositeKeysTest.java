/*
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Copyright (c) 2014 Martin Ouellet
 *
 */
package plugin.dvloader.trans.steps.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.util.Date;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.Test;

public class CompositeKeysTest {

	@Test
	public void testIdenticalValues() {
		
		CompositeKeys c1 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)});
		CompositeKeys c2 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)});	
		assertTrue(c1.equals(c2));
		assertEquals(c1.hashCode(), c2.hashCode());
		
		CompositeKeys c3 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100001)});
		assertFalse(c2.equals(c3));
		
		CompositeKeys c4 = new CompositeKeys(new Object[] {"v1","V",new Timestamp(100000)});
		assertFalse(c1.equals(c4));

		CompositeKeys c5 = new CompositeKeys(new Object[] {"v1","v2 ",new Timestamp(100000)});
		assertFalse(c1.equals(c4));
		
		
		CompositeKeys c6 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)}, new int[] {0,2});		
		CompositeKeys c7 = new CompositeKeys(new Object[] {"v1",new Timestamp(100000)});		
		assertTrue(c6.equals(c7));
		assertEquals(c6.hashCode(),c7.hashCode());
		//assertTrue(!c5.equals(c3));
		
		
		CompositeKeys c8 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)}, new int[] {0,1});		
		CompositeKeys c9 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)}, 0, 2);		
		assertTrue(c8.equals(c9));
		assertEquals(c8.hashCode(),c9.hashCode());
		
		CompositeKeys c10 = new CompositeKeys(new Object[] {"v1",new Integer(10)});		
		CompositeKeys c11 = new CompositeKeys(new Object[] {"v1",new Long(10)});		
		assertFalse(c10.equals(c11));
		//HERE, they will have same Hashcode()
		assertEquals(c10.hashCode(),c11.hashCode());
		
		
		CompositeKeys d1 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)},0,2);
		CompositeKeys d2 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(1)},0,2);	
		assertTrue(d1.equals(d2));

		CompositeKeys e1 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(100000)},1,2);
		CompositeKeys e2 = new CompositeKeys(new Object[] {"v1","v2",new Timestamp(1)},1,2);	
		assertFalse(e1.equals(e2));
		
	}

	@Test
	public void testDateValues() {
		//TODO:  check about these Dates......!!!!!
		//IMPORTANT: Date and timestamp can be equivalent or none depending on the other of comparison  
		CompositeKeys c1 = new CompositeKeys(new Object[] {"v1",new Date(10)});
		CompositeKeys c2 = new CompositeKeys(new Object[] {"v1",new Timestamp(10)});	
		assertTrue(c1.equals(c2));
		//the converse not TRUE!!!!
		assertFalse(c2.equals(c1));
		
		CompositeKeys c3 = new CompositeKeys(new Object[] {"v1",new java.sql.Date(10)});	
		assertTrue(c1.equals(c3));
		assertTrue(c3.equals(c1));
		
	}
	
	
	
}
