function generateMap(name, annotations, mapStyle, center, zoom) {
    L.TileLayer.d3_topoJSON = L.TileLayer.extend({
        onAdd: function (map) {
            L.TileLayer.prototype.onAdd.call(this, map);
            this._path = d3.geo.path().projection(function (d) {
                var point = map.latLngToLayerPoint(new L.LatLng(d[1], d[0]));
                return [point.x, point.y];
            });
            this.on("tileunload", function (d) {
                if (d.tile.xhr) d.tile.xhr.abort();
                if (d.tile.nodes) d.tile.nodes.remove();
                d.tile.nodes = null;
                d.tile.xhr = null;
            });
        },
        _loadTile: function (tile, tilePoint) {
            var self = this;
            this._adjustTilePoint(tilePoint);

            if (!tile.nodes && !tile.xhr) {
                tile.xhr = d3.json(this.getTileUrl(tilePoint), function (error, tjData) {
                    if (error) {
                        console.log(error);
                    } else {
                        var geoJson = topojson.feature(tjData, tjData.objects[self.options.layerName]);
                        tile.xhr = null;
                        tile.nodes = d3.select(map._container).select("svg").append("g");
                        tile.nodes.selectAll("path")
                            .data(geoJson.features).enter()
                            .append("path")
                            .attr("d", self._path)
                            .attr("class", self.options.class)
                            .attr("style", self.options.style);
                    }
                });
            }
        }
    });

    map = L.map(document.getElementById(name), { zoomControl:false }).setView([47.6062095, -122.332070], 12);
    map._initPathRoot();
    
    map.dragging.disable();
    map.touchZoom.disable();
    map.doubleClickZoom.disable();
    map.scrollWheelZoom.disable();
    map.keyboard.disable();

    // Disable tap handler, if present.
    if (map.tap) map.tap.disable();

    // Add a fake GeoJSON line to coerce Leaflet into creating the <svg> tag that d3_geoJson needs
    new L.geoJson({
        "type": "LineString",
        "coordinates": [[0, 0], [0, 0]]
    }).addTo(map);
    
    /*
    function rando(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}
    
    var landColors = {
  "farm": 1,
  "meadow": 1,
  "scrub": 1,
  "forest": 1,
  "farmyard": 1,
  "farmland": 1,
  "wood": 1,
  "park": 1,
  "cemetery": 1,
  "golf_course": 1,
  "grass": 1,
  "nature_reserve": 1,
  "pitch": 1,
  "common": 1,
  "residential": "#ddd",
  "industrial": "#b3c",
  "commercial": "#b3c",
  "retail": "#b3c",
  "parking": "#b3c",
  "quarry": "#b3c",
  "school": "#b3c",
  "hospital": "#b3c",
  "college": "#b3c",
  "university": "#b3c",
}
new L.TileLayer.d3_topoJSON("http://tile.openstreetmap.us/vectiles-land-usages/{z}/{x}/{y}.topojson", {
  class: "landuse",
  layerName: "vectile",
  style: function(d) {
    var c = landColors[d.properties.kind];
    if (!c) { c = "#fff"; }
    if (c == 1) {    // random greens
      c = "hsl(" + rando(100, 130) + ", " + rando(50,70) + "%, " + rando(30, 50) + "%)";
    }
    return "fill: " + c;
  }
}).addTo(map);
*/
    
    // Make the base map; a raster tile relief map from ESRI
    /*
    var esriRelief = 'http://server.arcgisonline.com/ArcGIS/rest/services/World_Shaded_Relief/MapServer/tile/{z}/{y}/{x}'
    var basemap = L.tileLayer(esriRelief, {
            attribution: '<a href="http://www.arcgis.com/home/item.html?id=9c5370d0b54f4de1b48a3792d7377ff2">ESRI shaded relief</a>, <a href="http://www.horizon-systems.com/NHDPlus/NHDPlusV2_home.php">NHDPlus v2</a>, OpenStreetMap',
            maxZoom: 13
    });
    basemap.addTo(map);
    */
    
    // Water Areas from OpenStreetMap
    new L.TileLayer.d3_topoJSON("http://tile.openstreetmap.us/vectiles-water-areas/{z}/{x}/{y}.topojson", {
        class: function(d) {
            return "water " + d.properties.kind;
        },
        layerName: "vectile",
        style: ""
    }).addTo(map);

    // Highways from OpenStreetMap
    new L.TileLayer.d3_topoJSON("http://tile.openstreetmap.us/vectiles-highroad/{z}/{x}/{y}.topojson", {
        class: function(d) {
            return "road " + d.properties.kind;
        },
        layerName: "vectile",
        style: ""
    }).addTo(map);
    
    var topPane = map._createPane('leaflet-top-pane', map.getPanes().mapPane);
    var topLayer = new L.tileLayer('http://{s}.tile.stamen.com/toner-labels/{z}/{x}/{y}.png', {
        maxZoom: 17
    }).addTo(map);
    topPane.appendChild(topLayer.getContainer());
    topLayer.setZIndex(7);
}