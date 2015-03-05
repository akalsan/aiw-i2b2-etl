package edu.emory.cci.aiw.neo4jetl;

/*
 * #%L
 * AIW Neo4j ETL
 * %%
 * Copyright (C) 2015 Emory University
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
import edu.emory.cci.aiw.neo4jetl.config.Configuration;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.graphdb.DynamicLabel;

import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.ReadableIndex;
import org.neo4j.kernel.impl.util.FileUtils;
import org.protempa.DataSource;
import org.protempa.DataSourceReadException;
import org.protempa.KnowledgeSourceReadException;
import org.protempa.PropositionDefinition;
import org.protempa.ProtempaException;
import org.protempa.dest.QueryResultsHandler;
import org.protempa.dest.QueryResultsHandlerCloseException;
import org.protempa.dest.QueryResultsHandlerInitException;
import org.protempa.query.QueryMode;
import org.protempa.dest.QueryResultsHandlerProcessingException;
import org.protempa.dest.QueryResultsHandlerValidationFailedException;
import org.protempa.proposition.Proposition;
import org.protempa.proposition.UniqueId;
import org.protempa.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author hrathod
 */
public class Neo4jQueryResultsHandler implements QueryResultsHandler {

	private static final Logger LOGGER
			= LoggerFactory.getLogger(Neo4jQueryResultsHandler.class);

	public static final Label NODE_LABEL = DynamicLabel.label("Data");
	
	public static final int DEFAULT_COMMIT_FREQUENCY = 2000;

	private final Map<UniqueId, Node> nodes;
	private final Map<String, RelationshipType> relations;
	private final Map<String, Derivations.Type> deriveType;
	private final Query query;
	private GraphDatabaseService db;
	private Map<String, PropositionDefinition> cache;
	private final PropositionDefinitionRelationForwardVisitor forwardVisitor;
	private final PropositionDefinitionRelationBackwardVisitor backwardVisitor;
	private final Configuration configuration;
	private Neo4jHome home;
	private final String keyType;
	private Transaction transaction;
	private int count;

	Neo4jQueryResultsHandler(Query inQuery, DataSource dataSource, Configuration configuration) throws QueryResultsHandlerInitException {
		this.nodes = new HashMap<>();
		this.relations = new HashMap<>();
		this.deriveType = new HashMap<>();
		this.query = inQuery;
		try {
			this.forwardVisitor = new PropositionDefinitionRelationForwardVisitor();
			this.backwardVisitor = new PropositionDefinitionRelationBackwardVisitor();
		} catch (KnowledgeSourceReadException ex) {
			throw new QueryResultsHandlerInitException(ex);
		}
		this.configuration = configuration;
		try {
			this.keyType = dataSource.getKeyType();
		} catch (DataSourceReadException ex) {
			throw new QueryResultsHandlerInitException(ex);
		}
	}

	@Override
	public void start(Collection<PropositionDefinition> cache) throws QueryResultsHandlerProcessingException {
		this.cache = new HashMap<>();
		for (PropositionDefinition pd : cache) {
			this.cache.put(pd.getId(), pd);
		}
		try {
			this.home = new Neo4jHome(this.configuration.getNeo4jHome());
			this.home.stopServer();
			File dbPath = this.home.getDbPath();
			LOGGER.info("Database path is {}", dbPath);
			if (this.query.getQueryMode() == QueryMode.REPLACE) {
				deleteAll();
			}
			GraphDatabaseFactory factory = new GraphDatabaseFactory();
			GraphDatabaseBuilder dbBuilder = factory.newEmbeddedDatabaseBuilder(dbPath.getAbsolutePath());
			this.db = dbBuilder.setConfig(GraphDatabaseSettings.node_keys_indexable, "__type,__uid").
					setConfig(GraphDatabaseSettings.node_auto_indexing, "true").
					setConfig(GraphDatabaseSettings.relationship_keys_indexable, "name").
					setConfig(GraphDatabaseSettings.relationship_auto_indexing,"true").
					newGraphDatabase();
		} catch (Exception ioe) {
			throw new QueryResultsHandlerProcessingException(ioe);
		}
		this.transaction = this.db.beginTx();
	}

	@Override
	public void handleQueryResult(String keyId,
			List<Proposition> propositions,
			Map<Proposition, List<Proposition>> forwardDerivations,
			Map<Proposition, List<Proposition>> backwardDerivations,
			Map<UniqueId, Proposition> references)
			throws QueryResultsHandlerProcessingException {

		// clear out the previous patient's data
		this.nodes.clear();

		if (++this.count % DEFAULT_COMMIT_FREQUENCY == 0) {
			try {
				if (this.transaction != null) {
					this.transaction.success();
					this.transaction.close();
					this.transaction = null;
				}
				this.transaction = this.db.beginTx();
			} catch (Exception ex) {
				if (this.transaction != null) {
					try {
						this.transaction.close();
					} catch (Exception ignore) {
					}
				}
				throw ex;
			}
		}

		try {
			// now create relationships for the references
			handleReferences(propositions, references);

			// now create relationships for the forward derivations
			handleDerivations(forwardDerivations, true);

			// now create relationships for the backward derivations
			handleDerivations(backwardDerivations, false);
		} catch (ProtempaException e) {
			transaction.failure();
			throw new QueryResultsHandlerProcessingException(e);
		} catch (Exception ex) {
			transaction.failure();
			throw new QueryResultsHandlerProcessingException(ex);
		}
	}

	@Override
	public void finish() throws QueryResultsHandlerProcessingException {
		// add/update a statistics node to save the number of patients added
		if (this.db != null) {
			try {
				this.transaction.success();
				this.transaction.close();
				this.transaction = null;
			} catch (Exception ex) {
				if (this.transaction != null) {
					try {
						this.transaction.close();
					} catch (Exception ignore) {
					}
				}
				throw ex;
			}
			try (Transaction tx = this.db.beginTx()) {
				try {
					ResourceIterable<Node> findNodesByLabelAndProperty = this.db.findNodesByLabelAndProperty(Neo4jStatistics.NODE_LABEL, null, null);
					Node node;
					try (ResourceIterator<Node> iterator = findNodesByLabelAndProperty.iterator()) {
						if (iterator.hasNext()) {
							node = iterator.next();
						} else {
							node = this.db.createNode(Neo4jStatistics.NODE_LABEL);
						}
					}
					ReadableIndex<Node> autoIndex = this.db.index().getNodeAutoIndexer().getAutoIndex();
					try (IndexHits<Node> hits = autoIndex.get("__type", this.keyType)) {
						node.setProperty(Neo4jStatistics.TOTAL_KEYS,
								hits.size());
					}

					tx.success();
				} catch (Exception ex) {
					tx.failure();
					throw new QueryResultsHandlerProcessingException(ex);
				}
			}
		}
	}

	@Override
	public void validate()
			throws QueryResultsHandlerValidationFailedException {
	}

	@Override
	public String[] getPropositionIdsNeeded() {
		return this.configuration.getPropositionIds();
	}

	@Override
	public void close() throws QueryResultsHandlerCloseException {
		if (this.db != null) {
			try {
				if (this.transaction != null) {
					this.transaction.failure();
					this.transaction.close();
					this.transaction = null;
				}
			} catch (Exception ex) {
				if (this.transaction != null) {
					try {
						this.transaction.close();
					} catch (Exception ignore) {
					}
				}
			} finally {
				this.db.shutdown();
			}
		}
		try {
			this.home.startServer();
		} catch (IOException | InterruptedException | CommandFailedException ex) {
			throw new QueryResultsHandlerCloseException(ex);
		}
	}

	private Node node(Proposition inProposition) throws QueryResultsHandlerProcessingException {
		String uid = inProposition.getUniqueId().getStringRepresentation();
		MapPropositionVisitor visitor = new MapPropositionVisitor();
		Node node = null;
		if (this.query.getQueryMode() == QueryMode.REPLACE) {
			node = this.db.createNode(NODE_LABEL);
		} else {
			int count = 0;
			for (Node n : this.db.findNodesByLabelAndProperty(null, "__uid", uid)) {
				if (count > 0) {
					throw new QueryResultsHandlerProcessingException("duplicate uid " + uid);
				}
				node = n;
				count++;
			}
			if (count == 0) {
				node = this.db.createNode(NODE_LABEL);
			}
		}
		assert node != null : "node was never set";
		String propId = inProposition.getId();
		PropositionDefinition pd = this.cache.get(propId);
		if (pd == null) {
			LOGGER.warn("No proposition definition with id {}", propId);
		}
		node.setProperty("displayName", pd != null ? pd.getDisplayName() : propId);
		node.setProperty("__type", inProposition.getId());
		inProposition.accept(visitor);
		for (Map.Entry<String, Object> entry : visitor.getMap().entrySet()) {
			if (entry.getValue() != null) {
				node.setProperty(entry.getKey(), entry.getValue());
			}
		}
		node.setProperty("__uid", uid);
		return node;
	}

	private Node getOrCreateNode(Proposition inProposition) throws QueryResultsHandlerProcessingException {
		if (!this.nodes.containsKey(inProposition.getUniqueId())) {
			this.nodes.put(
					inProposition.getUniqueId(), this.node(inProposition));
		}
		return this.nodes.get(inProposition.getUniqueId());
	}

	private void relate(Node source, Node target,
			RelationshipType inRelation) {
		Relationship relationship
				= source.createRelationshipTo(target, inRelation);
		relationship.setProperty("name", relationship.getType().name());
	}

	private RelationshipType getOrCreateRelation(String name) {
		if (!this.relations.containsKey(name)) {
			DynamicRelationshipType relationshipType
					= DynamicRelationshipType.withName(name);
			this.relations.put(name, relationshipType);
		}
		return this.relations.get(name);
	}

	private void handleDerivations(
			Map<Proposition, List<Proposition>> derivations, boolean forward)
			throws ProtempaException {
		for (Map.Entry<Proposition, List<Proposition>> entry
				: derivations.entrySet()) {
			Proposition sourceProposition = entry.getKey();
			Node source = this.getOrCreateNode(sourceProposition);
			for (Proposition targetProposition : entry.getValue()) {
				Node target = this.getOrCreateNode(targetProposition);
				String derivationType = this.derivationType(
						sourceProposition, targetProposition, forward);
				RelationshipType relation
						= this.getOrCreateRelation(derivationType);
				this.relate(source, target, relation);
			}
		}
	}

	private void handleReferences(List<Proposition> propositions,
			Map<UniqueId, Proposition> references)
			throws QueryResultsHandlerProcessingException {
		for (Proposition proposition : propositions) {
			Node source = this.getOrCreateNode(proposition);

			String[] names = proposition.getReferenceNames();
			for (String name : names) {
				List<UniqueId> ids = proposition.getReferences(name);
				RelationshipType relation = this.getOrCreateRelation(name);
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace(
							"Processing {} references with type {} for {}",
							ids.size(), name, proposition.getId());
				}
				for (UniqueId id : ids) {
					Proposition targetProposition = references.get(id);
					if (targetProposition != null) {
						Node target = this.getOrCreateNode(targetProposition);
						this.relate(source, target, relation);
					} else {
						LOGGER.error("No proposition for {}", id);
						throw new QueryResultsHandlerProcessingException(
								"No proposition for id " + id);
					}
				}
			}
		}
	}

	private String derivationType(Proposition source, Proposition target, boolean forward)
			throws ProtempaException {
		Derivations.Type result;
		String key = source.getId() + "->" + target.getId();
		String inverseKey = target.getId() + "->" + source.getId();
		PropositionDefinition definition
				= this.cache.get(source.getId());

		if (this.deriveType.containsKey(key)) {
			result = this.deriveType.get(key);
		} else if (this.deriveType.containsKey(inverseKey)) {
			result = Derivations.inverse(this.deriveType.get(inverseKey));
		} else {
			PropositionDefinitionRelationVisitor visitor
					= forward ? this.forwardVisitor : this.backwardVisitor;
			visitor.setTarget(this.cache.get(target.getId()));
			definition.acceptChecked(visitor);
			result = visitor.getRelation();
			this.deriveType.put(key, result);
			this.deriveType.put(inverseKey, Derivations.inverse(result));
		}
		return result.name();
	}

	private void deleteAll() throws Exception {
		LOGGER.info("Deleting all data from {}", this.home.getDbPath());
		GraphDatabaseFactory factory = new GraphDatabaseFactory();
		//Instantiate a database as a precaution to avoid deleting a directory that isn't a Neo4j database.
		GraphDatabaseService newEmbeddedDatabase = factory.newEmbeddedDatabase(this.home.getDbPath().getAbsolutePath());
		newEmbeddedDatabase.shutdown();
		FileUtils.deleteRecursively(this.home.getDbPath());
		LOGGER.info("Done deleting all data from {}", this.home.getDbPath());
	}

}
