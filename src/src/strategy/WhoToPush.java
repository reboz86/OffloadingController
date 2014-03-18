package src.strategy;

import java.util.Set;

import org.apache.abdera.i18n.iri.IRI;

public interface WhoToPush {
	
	public IRI whoToPush( Set<IRI> infected, Set<IRI> sane);

}
