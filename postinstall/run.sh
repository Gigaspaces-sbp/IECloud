#!/bin/bash

echo curl -i --data '@/opt/scripts/note.json' -X POST http://$1/api/notebook/import
curl -i --data '@/opt/scripts/note.json' -X POST http://$1/api/notebook/import

