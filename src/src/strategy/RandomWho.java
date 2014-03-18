package src.strategy;

import java.util.Collection;
import java.util.Random;
import java.util.Set;

import org.apache.abdera.i18n.iri.IRI;


public class RandomWho implements WhoToPush{

	@Override
	public IRI whoToPush(Set<IRI> infected, Set<IRI> sane) {
		return randomFromSet(sane); 
	}
	

	public static<T> T randomFromSet(Collection<T> set){
		Random rng = new Random ();
		int k = rng.nextInt(set.size());
		int j=0;
		for ( T r : set ){
			if ( j >= k )
				return r;
			j++;
		}
		return null;
	}


	
	

}
