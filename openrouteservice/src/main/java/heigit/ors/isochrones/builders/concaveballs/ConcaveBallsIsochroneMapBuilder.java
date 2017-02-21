/*|----------------------------------------------------------------------------------------------
 *|														Heidelberg University
 *|	  _____ _____  _____      _                     	Department of Geography		
 *|	 / ____|_   _|/ ____|    (_)                    	Chair of GIScience
 *|	| |  __  | | | (___   ___ _  ___ _ __   ___ ___ 	(C) 2014
 *|	| | |_ | | |  \___ \ / __| |/ _ \ '_ \ / __/ _ \	
 *|	| |__| |_| |_ ____) | (__| |  __/ | | | (_|  __/	Berliner Strasse 48								
 *|	 \_____|_____|_____/ \___|_|\___|_| |_|\___\___|	D-69120 Heidelberg, Germany	
 *|	        	                                       	http://www.giscience.uni-hd.de
 *|								
 *|----------------------------------------------------------------------------------------------*/
package heigit.ors.isochrones.builders.concaveballs;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import com.graphhopper.storage.EdgeEntry;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.DistancePlaneProjection;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import com.graphhopper.util.StopWatch;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.index.quadtree.Quadtree;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import org.apache.log4j.Logger;
import org.opensphere.geometry.algorithm.ConcaveHull;

import heigit.ors.isochrones.IsochroneSearchParameters;
import heigit.ors.isochrones.GraphEdgeMapFinder;
import heigit.ors.isochrones.Isochrone;
import heigit.ors.isochrones.IsochroneMap;
import heigit.ors.isochrones.builders.AbstractIsochroneMapBuilder;
import heigit.ors.routing.RouteSearchContext;
import heigit.ors.routing.graphhopper.extensions.AccessibilityMap;

public class ConcaveBallsIsochroneMapBuilder extends AbstractIsochroneMapBuilder 
{
	private final Logger LOGGER = Logger.getLogger(ConcaveBallsIsochroneMapBuilder.class.getName());

	private static double CONCAVE_HULL_THRESHOLD = 0.012;

	private double searchWidth = 0.0007; 
	private double pointWidth = 0.0005;
	private double visitorThreshold = 0.0013;
	private Envelope searchEnv = new Envelope();
	private GeometryFactory _geomFactory;
	private PointItemVisitor visitor = null;
	private List<Point2D> prevIsoPoints = null;
	private RouteSearchContext _searchContext;

	public void initialize(RouteSearchContext searchContext) {
		// TODO Auto-generated method stub
		_geomFactory = new GeometryFactory();
		_searchContext = searchContext;		
	}

	public IsochroneMap compute(IsochroneSearchParameters parameters) throws Exception {
		StopWatch	swTotal = null;
		StopWatch sw = null;
		if (LOGGER.isDebugEnabled())
		{
			swTotal = new StopWatch();
			swTotal.start();
			sw = new StopWatch();
			sw.start();
		}

		// 1. Find all graph edges for a given cost.
		double maxSpeed = _searchContext.getEncoder().getMaxSpeed();

		Coordinate loc = parameters.getLocation();
		IsochroneMap isochroneMap = new IsochroneMap(loc);

		AccessibilityMap edgeMap = GraphEdgeMapFinder.findEdgeMap(_searchContext, parameters);

		if (LOGGER.isDebugEnabled())
		{
			sw.stop();

			LOGGER.debug("Find edges: " + sw.getSeconds());
		}

		if (edgeMap.isEmpty())
			return isochroneMap;

		List<Point2D> isoPoints = new ArrayList<Point2D>(edgeMap.getMap().size());

		if (LOGGER.isDebugEnabled())
		{
			sw = new StopWatch();
			sw.start();
		}

		markDeadEndEdges(edgeMap);

		if (LOGGER.isDebugEnabled())
		{
			sw.stop();
			LOGGER.debug("Mark dead ends: " + sw.getSeconds());
		}

		int nRanges = parameters.getRanges().length;
		double metersPerSecond = maxSpeed / 3.6;

		double prevCost = 0;
		for (int i = 0; i < nRanges; i++) {
			double isoValue = parameters.getRanges()[i];

			if (LOGGER.isDebugEnabled())
			{
				sw = new StopWatch();
				sw.start();
			}

			GeometryCollection points = buildIsochrone(edgeMap, isoPoints, loc.x, loc.y, isoValue, prevCost,maxSpeed, 0.85);

			if (LOGGER.isDebugEnabled())
			{
				//	 savePoints(points, "D:\\isochrones3.shp");
				sw.stop();
				LOGGER.debug(i + " Find points: " + sw.getSeconds() + " " + points.getNumGeometries());

				sw = new StopWatch();
				sw.start();
			}

			addIsochrone(isochroneMap, points, isoValue, metersPerSecond * isoValue);

			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Build concave hull: " + sw.stop().getSeconds());

			prevCost = isoValue;
		}

		if (LOGGER.isDebugEnabled())
			LOGGER.debug("Total time: " + swTotal.stop().getSeconds());

		return isochroneMap;
	}

	private void addIsochrone(IsochroneMap isochroneMap, GeometryCollection points, double isoValue, double maxRadius)
	{
		if (points.isEmpty())
			return;
		
		ConcaveHull ch = new ConcaveHull(points, CONCAVE_HULL_THRESHOLD);
		Geometry geom = ch.getConcaveHull();
		
		if (geom instanceof GeometryCollection)
		{
			GeometryCollection geomColl = (GeometryCollection)geom;
			if (geomColl.isEmpty())
				return;
		}
		
		Polygon poly = (Polygon)geom;

		copyConvexHullPoints(poly);

		isochroneMap.addIsochrone(new Isochrone(poly, isoValue, maxRadius));
	}

	private void markDeadEndEdges(AccessibilityMap edgeMap)
	{
		TIntObjectMap<EdgeEntry> map = edgeMap.getMap();
		TIntObjectMap<Integer> result = new TIntObjectHashMap<Integer>(map.size()/20);

		for (TIntObjectIterator<EdgeEntry> it = map.iterator(); it.hasNext();) {
			it.advance();

			EdgeEntry edge = it.value();
			if (edge.originalEdge == -1)
				continue;

			result.put(edge.parent.originalEdge, 1);
		}

		for (TIntObjectIterator<EdgeEntry> it = map.iterator(); it.hasNext();) {
			it.advance();

			EdgeEntry edge = it.value();
			if (edge.originalEdge == -1)
				continue;

			if (!result.containsKey(edge.originalEdge))
				edge.edge =-2;
		}
	}

	public void addPoint(List<Point2D> points, Quadtree tree, double lon, double lat, boolean checkNeighbours) {
		if (checkNeighbours)
		{
			visitor.setPoint(lon, lat);
			searchEnv.init(lon - searchWidth, lon + searchWidth, lat - searchWidth, lat + searchWidth);
			tree.query(searchEnv, visitor);
			if (!visitor.isNeighbourFound()) {
				Envelope env = new Envelope(lon - pointWidth, lon + pointWidth, lat - pointWidth, lat + pointWidth);
				Point2D p = new Point2D.Double(lon, lat);
				tree.insert(env, p);
				points.add(p);
			}
		}
		else
		{
			Envelope env = new Envelope(lon - pointWidth, lon + pointWidth, lat - pointWidth, lat + pointWidth);
			Point2D p = new Point2D.Double(lon, lat);
			tree.insert(env, p);
			points.add(p);
		} 
	}

	private void addBufferPoints(List<Point2D> points, Quadtree tree, double lon0, double lat0, double lon1,
			double lat1, boolean addLast, boolean checkNeighbours, double bufferSize) {
		double dx = (lon0 - lon1);
		double dy = (lat0 - lat1);
		double norm_length = Math.sqrt((dx * dx) + (dy * dy));
		double scale = bufferSize /norm_length;

		double dx2 = -dy*scale;
		double dy2 = dx*scale;

		addPoint(points, tree, lon0 + dx2, lat0 + dy2, checkNeighbours);
		addPoint(points, tree, lon0 - dx2, lat0 - dy2, checkNeighbours);

		// add a middle point if two points are too far from each other
		if (norm_length > 2*bufferSize)
		{
			addPoint(points, tree, (lon0 + lon1)/2 + dx2, (lat0 + lat1)/2 + dy2, checkNeighbours);	
			addPoint(points, tree, (lon0 + lon1)/2 - dx2, (lat0 + lat1)/2 - dy2, checkNeighbours);
		}

		if (addLast) {
			addPoint(points, tree, lon1 + dx2, lat1 + dy2, checkNeighbours);
			addPoint(points, tree, lon1 - dx2, lat1 - dy2, checkNeighbours);
		}
	}

	private GeometryCollection buildIsochrone(AccessibilityMap edgeMap, List<Point2D> points, double lon, double lat,
			double isolineCost, double prevCost,  double maxSpeed, double detailedGeomFactor) {
		TIntObjectMap<EdgeEntry> map = edgeMap.getMap();

		points.clear();

		if (prevIsoPoints != null)
			points.addAll(prevIsoPoints);

		GraphHopperStorage graph = _searchContext.getGraphHopper().getGraphHopperStorage();
		NodeAccess nodeAccess = graph.getNodeAccess();
		int maxNodeId = graph.getNodes();

		EdgeEntry edgeEntry = edgeMap.getEdgeEntry();
		EdgeEntry goalEdge = edgeEntry;

		DistanceCalc dcFast = new DistancePlaneProjection();
		double bufferSize = 0.0018;
		Quadtree qtree = new Quadtree();
		visitor = new PointItemVisitor(lon, lat, visitorThreshold);
		double detailedZone = isolineCost * detailedGeomFactor;
		double isolineDist = isolineCost * maxSpeed*1000/3600;
		double lonN, latN;

		double defaultSearchWidth = 0.0005;
		double defaultVisitorThreshold = 0.0045;
		double defaulPointWidth =  0.0045;

		if (isolineDist*0.5 < 20000)
		{
			defaultVisitorThreshold = 0.002;
			defaulPointWidth = 0.002;
		}

		for (TIntObjectIterator<EdgeEntry> it = map.iterator(); it.hasNext();) {
			it.advance();
			int nodeId = it.key();

			if (nodeId == -1 || nodeId > maxNodeId)
				continue;

			goalEdge = it.value();

			int edgeId = goalEdge.originalEdge;

			if (edgeId == -1)
				continue;

			EdgeIteratorState iter = graph.getEdgeIteratorState(edgeId, nodeId);

			float maxCost = (float) (goalEdge.weight);
			float minCost = (float) (goalEdge.parent.weight);

			if (maxCost < prevCost)
				continue;

			searchWidth = defaultSearchWidth; 
			visitorThreshold = defaultVisitorThreshold; 
			pointWidth = defaulPointWidth;		

			if (goalEdge.edge != -2)
			{
				if (maxCost > isolineCost*0.95)
				{
					searchWidth = 0.0004; 
					visitorThreshold = 0.003; 
					pointWidth = 0.003;
				}
				else if (maxCost < isolineCost*0.8)
				{
					lonN = nodeAccess.getLon(nodeId);
					latN = nodeAccess.getLat(nodeId);
					double distTo = dcFast.calcDist(lat, lon, latN, lonN);

					if (distTo < isolineDist*0.3)
					{
						searchWidth = 0.001; 
						visitorThreshold = 0.0015; 
						pointWidth = 0.0015;
					}
					else if (distTo < isolineDist*0.6)
					{
						searchWidth = 0.0007; 
						visitorThreshold = 0.007; 
						pointWidth = 0.007; 
					}
					else if (distTo < isolineDist*0.7)
					{
						searchWidth = 0.0005; 
						visitorThreshold = 0.005; 
						pointWidth = 0.005; 
					}  
				}
			}

			visitor.setThreshold(visitorThreshold);

			if (isolineCost >= maxCost) {

				if (goalEdge.edge == -2)
				{
					addPoint(points, qtree, nodeAccess.getLon(nodeId), nodeAccess.getLat(nodeId), true);
				}
				else
				{
					double edgeDist = iter.getDistance();

					if (((maxCost >= detailedZone && maxCost <= isolineCost) || edgeDist > 300)) 
					{
						boolean detailedShape = (edgeDist > 300);
						// always use mode=3, since other ones do not provide correct results
						PointList pl = iter.fetchWayGeometry(3);
						int size = pl.getSize();
						if (size > 0) {
							double lat0 = pl.getLat(0);
							double lon0 = pl.getLon(0);
							double lat1, lon1;

							if (detailedShape)
							{
								for (int i = 1; i < size; ++i) {
									lat1 = pl.getLat(i);
									lon1 = pl.getLon(i);

									addBufferPoints(points, qtree, lon0, lat0, lon1, lat1, i == size - 1, true, bufferSize);

									lon0 = lon1;
									lat0 = lat1;
								}
							}
							else
							{
								for (int i = 1; i < size; ++i) {
									lat1 = pl.getLat(i);
									lon1 = pl.getLon(i);

									addPoint(points, qtree, lon0, lat0, true);
									if (i == size -1)
										addPoint(points, qtree, lon1, lat1, true);

									lon0 = lon1;
									lat0 = lat1;
								}
							}
						}
					} else {
						addPoint(points, qtree, nodeAccess.getLon(nodeId), nodeAccess.getLat(nodeId), true);
					}
				}
			} else {
				if ((minCost < isolineCost && maxCost >= isolineCost)) {

					PointList pl = iter.fetchWayGeometry(3);

					int size = pl.getSize();
					if (size > 0) {
						double edgeCost = maxCost - minCost;
						double edgeDist = iter.getDistance();
						double costPerMeter = edgeCost / edgeDist;
						double distPolyline = 0.0;

						double lat0 = pl.getLat(0);
						double lon0 = pl.getLon(0);
						double lat1, lon1;

						for (int i = 1; i < size; ++i) {
							lat1 = pl.getLat(i);
							lon1 = pl.getLon(i);

							distPolyline += dcFast.calcDist(lat0, lon0, lat1, lon1);

							double distCost = minCost + distPolyline * costPerMeter;
							if (distCost >= isolineCost) {
								double segLength = (1 - (distCost - isolineCost) / edgeCost);
								double lon2 = lon0 + segLength * (lon1 - lon0);
								double lat2 = lat0 + segLength * (lat1 - lat0);

								addBufferPoints(points, qtree, lon0, lat0, lon2, lat2, true, false, bufferSize);

								break;
							} else {
								addBufferPoints(points, qtree, lon0, lat0, lon1, lat1, false, true, bufferSize);
							}

							lat0 = lat1;
							lon0 = lon1;
						}
					}
				} 
			}
		}

		Geometry[] geometries = new Geometry[points.size()];
		for (int i = 0;i < points.size();++i)
		{
			Point2D p = points.get(i);
			geometries[i] = _geomFactory.createPoint(new Coordinate(p.getX(), p.getY()));
		}

		return new GeometryCollection(geometries, _geomFactory);
	}

	private void copyConvexHullPoints(Polygon poly)
	{
		LineString ring = (LineString)poly.getExteriorRing();		
		if (prevIsoPoints == null)
			prevIsoPoints = new ArrayList<Point2D>(ring.getNumPoints());
		else
			prevIsoPoints.clear();
		for (int i = 0; i< ring.getNumPoints(); ++i)
		{
			Point p = ring.getPointN(i);
			prevIsoPoints.add(new Point2D.Double(p.getX(), p.getY()));
		}
	}
}
