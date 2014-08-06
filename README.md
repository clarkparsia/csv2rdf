csv2rdf
=======

csv2rdf is a simple tool for generating RDF output from CSV/TSV files. The conversion is done by a template file
that shows how the RDF output will look for one row. See [examples/cars](examples/cars) for details. 

Building
--------

`ant clean dist` will create a local build in the `dist` sub-directory.

Running
-------

You can run the tool with the command `java -jar dist/lib/csv2rdf.jar` followed by arguments.

You can see the help screen with the command `java -jar dist/lib/csv2rdf.jar help convert`.

You can run the conversion for the example using `java -jar dist/lib/csv2rdf.jar examples/cars/template.ttl examples/cars/cars.csv cars.ttl`. 